#!/bin/bash
# This script attempts to automate everything in the following forum posts
#
#  Sage install on linux - http://forums.sagetv.com/forums/showthread.php?p=585243#post585243
#  Sage OpenDCT install
#
# updated 7 March 2016 - 2221
# updated 10 July 2016 - 2247 - update download links to current version
#

echo ""
echo "========= SageTV Autoinstall ========"

# TODO:
# 1) Detect and Backup a current install
# - look for /opt/sagetv/server/ directory
# - if exists, continue, else prompt user if current install exists?  Allow them to enter location
# - if no current install go to step to
# - if current install exists, backup Wiz.Bin and Sage.Properties files

# 2) Determine version of linux we have.
#   I've technically built this for Ubuntu.. so it assumes ubuntu commands, etc.  Otherwise, tell the user?


echo ""
echo "========= Determine Linux Version ========"

# Does this appear to be Ubuntu
if grep -q ubuntu /proc/version; then

  # if this looks like ubuntu, check the relase information
  LINUX_VERSION="/etc/lsb-release"

  # if the file exists
  if [ -f $LINUX_VERSION ]
  then
    UBUNTU_VERSION=$(grep DESCRIP /etc/lsb-release | cut -d '=' -f 2 | tr -d '"')
    echo " OS appears to be Ubuntu: $UBUNTU_VERSION"
  fi
else

  # This does not appear to be Ubuntu, warn the user and then let them decide what to do
  echo " Could not validate this to be an UBUNTU build.  This script has only been tested on"
  echo " Ubuntu 14.04.3 LTS - 64 bit.  It will probably work on other Debian 64bit builds."
  echo ""
  read -n1 -p "Press [C] to [C]ontinue,  Press any other key to exit. " yn
  case $yn in
    [Cc]* ) echo "Continuing"; break;;
    * ) echo ""; exit;;
  esac
fi
echo ""
read -t 5 -p "Press any key to continue, install will auto resume in 5 seconds" -n1


# 3) Check for and install updates
# Log in as the user you created during installation and with the configured password. Run the below commands.
echo ""
echo "========= System Updates ========"
echo " Check for and get OS updates"
echo ""
echo " apt-get update (quiet)"
sudo apt-get -y --force-yes -qq update
echo " apt-get upgrade (quiet)"
sudo apt-get -y --force-yes -qq upgrade
echo " apt-get dist-upgrade (quiet)"
sudo apt-get -y --force-yes -qq dist-upgrade
echo " apt-get autoclean (quiet)"
sudo apt-get -y --force-yes -qq autoclean

echo ""
read -t 5 -p "Press any key to continue, install will auto resume in 5 seconds" -n1



# 4) get my ip information

HOST_IP=$(ifconfig eth0 | grep "inet " | awk -F'[: ]+' '{ print $4 }')
# get my netmask
HOST_NETMASK=$(ifconfig eth0 | grep "inet " | awk -F'[: ]+' '{ print $8 }')
# get my gateway
HOST_GATEWAY=$(route -n | grep UG | awk '{print $2}')

echo ""
echo "========= System Network Settings ========"
echo " IP      = $HOST_IP"
echo " NETMASK = $HOST_NETMASK"
echo " GATEWAY = $HOST_GATEWAY"
echo ""
read -t 5 -p "Press any key to continue, install will auto resume in 5 seconds" -n1


# get ready to install Java

JAVA_VERSION=$(java -version 2>&1 | awk -F '"' '/version/ {print $2}')

if [ "JAVA_VERSION" = "1.8.0_72" ]
then
    echo ""
    echo " ********* Java is already installed ($JAVA_VERSION) ********"

else
    echo ""
    echo "========= Install Java 8  ========"
    echo " Add the Java Repository"
    sudo add-apt-repository -y ppa:webupd8team/java
    echo ""
    echo " Do an update to know what is in repository (quiet)"
    sudo apt-get -y --force-yes -qq update
    echo ""
    echo " Install Java 8"
    sudo apt-get install -y --force-yes oracle-java8-installer
fi

# install old java 7?
#apt-get install default-jre-headless
#dpkg -i opendct_x.x.x-x_arch.deb
echo ""
read -t 5 -p "Press any key to continue, install will auto resume in 5 seconds" -n1



# if a SageTV install exists, is it this version? if so... skip?
SAGE_PROPERTIES="/opt/sagetv/server/Sage.properties"

# if the file exists
if [ -f $SAGE_PROPERTIES ]
then
    CURRENT_SAGE_VERSION=$(grep "version=SageTV" /opt/sagetv/server/Sage.properties | awk '{print $2}')

    if [ "$CURRENT_SAGE_VERSION" = "V9.0.4.287" ]
    then
        echo ""
        echo "********* SageTV already installed ($CURRENT_SAGE_VERSION) ********"
    else
    #if I am not that same version... do an install
        echo ""
        echo "========= Download SageTV .deb  ========"
        sudo wget https://bintray.com/artifact/download/opensagetv/sagetv/sagetv/9.0.4.287/sagetv-server_9.0.4_amd64.deb

        echo ""
        echo "========= Install SageTV .deb  ========"
        # Install the package to /opt/sagetv/server/
        sudo dpkg -i sagetv-server_9.0.4_amd64.deb
    fi

else
    echo ""
    echo "========= Download SageTV .deb  ========"
    sudo wget https://bintray.com/artifact/download/opensagetv/sagetv/sagetv/9.0.4.287/sagetv-server_9.0.4_amd64.deb

    echo ""
    echo "========= Install SageTV .deb  ========"
    # Install the package to /opt/sagetv/server/
    sudo dpkg -i sagetv-server_9.0.4_amd64.deb
fi
echo ""
read -t 5 -p "Press any key to continue, install will auto resume in 5 seconds" -n1



echo ""
echo "========= Add Firewall Entries ========"
# open network ports
sudo ufw allow proto tcp to any port 22     2>/dev/null
sudo ufw allow proto tcp to any port 7760   2>/dev/null
sudo ufw allow proto tcp to any port 7818   2>/dev/null
sudo ufw allow proto tcp to any port 8018   2>/dev/null
sudo ufw allow proto tcp to any port 8080   2>/dev/null
sudo ufw allow proto udp to any port 8271   2>/dev/null
sudo ufw allow proto tcp to any port 31099  2>/dev/null
sudo ufw allow proto udp to any port 31100  2>/dev/null
sudo ufw allow proto tcp to any port 42024  2>/dev/null

sudo iptables -I INPUT -p udp -m udp --sport 65001 -j ACCEPT

#/etc/init.d/iptables save
echo ""
read -t 5 -p "Press any key to continue, install will auto resume in 5 seconds" -n1


# install OpenDCT or PrimeNetEncoder
# prompt the user
#echo ""
#while true; do
#    read -p "Do you wish to install [O]penDCT, [P]rimeNetEncoder or [S]top? " yn
#    case $yn in
#        [Oo]* ) echo "OpenDCT"; break;;
#        [Pp]* ) echo "PNE"; break;;
#        [Ss]* ) exit;;
#        * ) echo "Please answer Open, Prime or Stop.";;
#    esac
#done



# If OpenDCT is the same version
OPENDCT_OLD="/opt/opendct/conf/opendct.properties"
OPENDCT_NEW="/etc/opendct/conf/opendct.properties"

# if the opendct dir exists, tell folks the version, see if we should reinstall?
if [ -d OPENDCT_OLD ]; then
  # Control will enter here if $DIRECTORY exists.
  # print version number. then ask if we want to reinstall
  OPENDCT_VER=$(grep version.program /opt/opendct/conf/opendct.properties | cut -d = -f 2)
fi

if [ -d OPENDCT_NEW ]; then
  OPENDCT_VER=$(grep version.program /etc/opendct/conf/opendct.properties | cut -d = -f 2)
fi

if [ -n "$OPENDCT_VER" ]; then
  echo ""
  echo "========= OpenDCT v$OPENDCT_VER is already installed  ========="

else
  echo ""
  echo "========= Installing OpenDCT ========="

  echo ""
  echo "========= Download OpenDCT .deb Beta  ========"
  sudo wget https://bintray.com/opendct/Beta/download_file?file_path=releases%2F0.5.7%2Fopendct_0.5.7-1_amd64.deb -O opendct_0.5.7-1_amd64.deb

  echo ""
  echo "========= Install OpenDCT .deb Beta  ========"
  sudo dpkg -i opendct_0.5.7-1_amd64.deb

  echo ""
  echo "========= Update OpenDCT Firewall Rules ========"
  sudo ufw allow proto tcp to any port 9000:9100 2>/dev/null
  sudo ufw allow proto udp to any port 8300:8500 2>/dev/null
  sudo ufw allow proto tcp to any port 7818 2>/dev/null
  sudo ufw allow proto udp to any port 8271 2>/dev/null


  echo ""
  echo "========= OpenDCT First Run (~30 seconds) ========"
  sudo service opendct stop
  read -t 5 -p "Waiting for opendct service to stop" -n1

  # Because of possible access issues, chown (change ownership) of the main opendct directories to root
  # Because this is being run as root, this shouldn't be an issue
  # TODO: install and run all this as a different user
  #sudo chown -R root:root /var/log/opendct/
  #sudo chown -R root:root /etc/opendct/
  #sudo chown -R root:root /opt/opendct/

  #sudo chmod a+x /opt/opendct/console-only
  timeout -k 20s 20s "sudo" "/opt/opendct/console-only"
fi
echo ""
read -t 5 -p "Press any key to continue, install will auto resume in 5 seconds" -n1


echo ""
echo "========= Update Sage for Network Encoders ========"

# Make sure sage is shut down
sudo service sagetv stop

echo ""
echo "========= Validate discovery port ========"

# is encoding discovery port set?
# this returns either the port, or is "" if the variable does not exist
ENCODING_PORT=$(grep encoding_discovery_port /opt/sagetv/server/Sage.properties | cut -d = -f 2)

# if there is no encoding port... add it to the end of the file
if [ -z "$ENCODING_PORT" ]; then
    echo " Adding network encoder port to Sage.properties file"
    echo "" >> /opt/sagetv/server/Sage.properties
    echo "encoding_discovery_port=8271" >> /opt/sagetv/server/Sage.properties
fi

# now print the port in the Sage.properties file
ENCODING_PORT=$(grep encoding_discovery_port /opt/sagetv/server/Sage.properties | cut -d = -f 2)
echo " Sage.properties - encoding_discovery_port=$ENCODING_PORT"

# what if it is there but a different port?
# currently it should be left as is.

echo ""
read -t 5 -p "Press any key to continue, install will auto resume in 5 seconds" -n1



echo ""
echo "========= Validate Encoder Discovery ========"

# If exists and set to false, remove line from config file
  sed -i.bak '/network_encoder_discovery=false/d' /opt/sagetv/server/Sage.properties

# If network encoder discovery not exist, set it.
# this returns either true/false, or is "" if the variable does not exist
NETWORK_DISCOVERY=$(grep network_encoder_discovery /opt/sagetv/server/Sage.properties | cut -d = -f 2)

# if there is no encoding port... add it to the end of the file
if [ -z "$NETWORK_DISCOVERY" ]; then
    echo " Adding network discovery to Sage.properties file"
    echo "" >> /opt/sagetv/server/Sage.properties
    echo "network_encoder_discovery=true" >> /opt/sagetv/server/Sage.properties
fi

# now print the discovery status in the Sage.properties file
NETWORK_DISCOVERY=$(grep network_encoder_discovery /opt/sagetv/server/Sage.properties | cut -d = -f 2)
echo " Sage.properties - network_encoder_discovery=$NETWORK_DISCOVERY"


echo ""
read -t 5 -p "Press any key to continue, install will auto resume in 5 seconds" -n1

echo ""
echo "========= Ensure OpenDCT auto starts at boot ========"

sudo update-rc.d -f opendct defaults

echo ""
echo "========= Start OpenDCT Service ========"

sudo service opendct start

echo ""
echo "========= Start SageTV Service ========"

sudo service sagetv start

