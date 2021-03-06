/*
 * Copyright 2015 The SageTV Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package sage;

import sage.media.format.ContainerFormat;
import sage.media.format.MPEGParser2;
import sage.media.format.MediaFormat;
import sage.media.format.VideoFormat;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class MediaServerRemuxer
{
  // This is only used in reference to incoming packets since it's possible that one day we will
  // need to handle other sizes and this might be turned into a variable instead of a constant. The
  // outgoing TS packets are always 188 and should not use this variable.
  private static final int TS_ALIGN = 188; // Normal TS packet length.

  // This value must be a multiple of 188. This value can be made higher, but this number is also
  // used to align the bytes to be transferred into the remuxer and as a result is the only amount
  // of bytes that will be transferred at one time unless we are closing. Significantly higher
  // values could also result in unpredictable behavior since the remuxer does seem to have a limit
  // on how much data it will take at one time. Increasing this value will directly increase
  // latency. Decreasing this value will reduce the amount of byte copies, but will increase the
  // frequency that JNI is called which can increase overall CPU usage. The total buffer size is
  // used for alignment so we can avoid performing modulus with TS_ALIGN on every single bulk
  // transfer to see if the last packet will come up short of a full packet.
  private static final int MAX_TRANSFER = 176 * TS_ALIGN; // a little over 32K

  // Any max size that is not a multiple of 188 is a waste since the extra bytes will not be used.
  // This value is picked because I have not observed anything that actually needed 20MB, and we
  // have to set a limit somewhere. Most detection needs less than 10MB, so based on the initial
  // size being 1/4 of the max size, it might be resized once and at most twice, but it's rare. This
  // value is also the most data that will be processed before the remuxer assumes you did something
  // wrong and switches to full auto-detection.
  private static final int MAX_INIT_BUFFER_SIZE = 111550 * TS_ALIGN; // 20MB

  // Large random number.
  private static final long SWITCH_BYTES_LIMIT = 8388608; // 8MB

  private static final Map<File, MediaServerRemuxer> remuxerMap =
      new ConcurrentHashMap<File, MediaServerRemuxer>();

  private boolean closed;

  private final Object switchLock;
  private boolean switching;
  private final MediaServer.Connection mediaServer;
  private String switchFilename;
  private int switchUploadId;
  private long switchData;
  private boolean interAssist;

  private File currentFile;
  private final RemuxWriter writer;
  private ByteBuffer writeBuffer;
  private FileChannel fileChannel;
  private MPEGParser2.Remuxer2 remuxer2;

  private boolean tsSynced;
  private int partialTransferIndex;
  private final byte partialTransfer[];

  private byte[] transferBuffer;
  private int initOffset;
  private long bufferIndex;
  private long bufferLimit;
  private final AtomicLong fileSize;
  private ContainerFormat containerFormat;
  private boolean h264;
  private boolean mpeg2;
  private int videoPid;

  private int inputFormat;
  private int outputFormat;
  private MPEGParser2.StreamFormat streamFormat;
  private MPEGParser2.SubFormat subFormat;
  private MPEGParser2.TuneStringType tuneStringType;
  private int channel;
  private int tsid;
  private int data1;
  private int data2;
  private int data3;

  /**
   * Create a new media server remuxer with auto-detection.
   * <p/>
   * The input stream is assumed TS.
   *
   * @param fileChannel  The starting FileChannel to be used for writing.
   * @param outputFormat The format of the remuxed stream.
   * @param isTV         Is the incoming stream from a TV Tuner?
   * @param mediaServer  The media server creating this instance.
   */
  public MediaServerRemuxer(FileChannel fileChannel, int outputFormat, boolean isTV, MediaServer.Connection mediaServer)
  {
    // Any initial size that is not a multiple of 188 is a waste since the extra bytes will not be used.
    this(fileChannel,
        27888 * TS_ALIGN /* 5MB; one quarter the max size */, outputFormat,
        isTV ? MPEGParser2.StreamFormat.ATSC : MPEGParser2.StreamFormat.FREE,
        MPEGParser2.SubFormat.UNKNOWN,
        MPEGParser2.TuneStringType.CHANNEL,
        0, 0, 0, 0, 0,
        mediaServer);
  }

  /**
   * Create a new media server remuxer.
   *
   * @param fileChannel    The starting FileChannel to be used for writing.
   * @param initData       The starting buffer size used to buffer data during detection. It will
   *                       automatically expand if necessary.
   * @param outputFormat   The format of the remuxed stream.
   * @param streamFormat   The format of the incoming A/V streams.
   * @param subFormat      The sub-format of the incoming A/V streams.
   * @param tuneStringType The format of the provided tune string. If a tune string is not in use,
   *                       be sure to set this value to CHANNEL.
   * @param channel        If the tune string type is a channel, this will set the channel. 1 = default
   * @param tsid           The program number to select from a TS.
   * @param data1          The first number of a hyphenated tune string.
   * @param data2          The second number of a hyphenated tune string.
   * @param data3          The third number of a hyphenated tune string.
   * @param mediaServer    The media server creating this instance.
   * @throws IllegalArgumentException If the media server is null or the remuxer can't be opened.
   */
  public MediaServerRemuxer(FileChannel fileChannel, int initData,
                            int outputFormat,
                            MPEGParser2.StreamFormat streamFormat,
                            MPEGParser2.SubFormat subFormat,
                            MPEGParser2.TuneStringType tuneStringType,
                            int channel,
                            int tsid,
                            int data1,
                            int data2,
                            int data3,
                            MediaServer.Connection mediaServer) throws IllegalArgumentException
  {
    if (mediaServer == null)
    {
      throw new IllegalArgumentException("The media server cannot be null.");
    }

    closed = false;

    switchLock = new Object();
    switching = false;
    this.mediaServer = mediaServer;
    currentFile = mediaServer.getFile();
    switchFilename = null;
    switchUploadId = 0;
    switchData = 0;
    interAssist = true;

    bufferIndex = 0;
    bufferLimit = 0;
    fileSize = new AtomicLong(0);
    containerFormat = null;
    h264 = false;
    mpeg2 = false;
    videoPid = -1;
    initOffset = 0;

    this.inputFormat = MPEGParser2.REMUX_TS;
    this.outputFormat = outputFormat;
    this.streamFormat = streamFormat;
    this.subFormat = subFormat;
    this.tuneStringType = tuneStringType;
    this.channel = channel;
    this.tsid = tsid;
    this.data1 = data1;
    this.data2 = data2;
    this.data3 = data3;

    tsSynced = true;
    partialTransferIndex = 0;
    partialTransfer = new byte[MAX_TRANSFER];

    // This is the most that data that will be buffered before writing to disk. The output from the
    // remuxer is typically around 5KB. When the next write from the remuxer is larger than the
    // available space in this buffer, it will be written to disk. If this buffer is still too small
    // to fit the requested write, this buffer will be expanded. Keep this number relatively small
    // to keep latency down.
    writeBuffer = ByteBuffer.allocateDirect(16384);

    transferBuffer = new byte[initData];
    this.fileChannel = fileChannel;
    writer = new RemuxWriter();

    remuxer2 = MPEGParser2.openRemuxer(inputFormat, outputFormat, streamFormat, subFormat, tuneStringType, channel, tsid, data1, data2, data3, writer);

    if (remuxer2 == null)
    {
      throw new IllegalArgumentException("Unable to initialize the remuxer.");
    }

    remuxerMap.put(currentFile, this);
  }


  public void setBufferLimit(long bufferLimit)
  {
    this.bufferLimit = bufferLimit;
  }

  /**
   * Get if the remuxer is initialized.
   * <p/>
   * This essentially also means data is being written out which can be verified by checking the
   * size.
   *
   * @return If the remuxer is currently initialized.
   */
  public boolean isInitialized()
  {
    return containerFormat != null;
  }

  /**
   * Get the current container format.
   * <p/>
   * This will return null if the container format has not been detected.
   *
   * @return The container format.
   */
  public ContainerFormat getContainerFormat()
  {
    return containerFormat;
  }

  /**
   * Get the current file size.
   * <p/>
   * This is a thread-safe method.
   *
   * @return The current file size.
   */
  public long getFileSize()
  {
    return fileSize.get();
  }

  /**
   * Get the current remux output mode.
   *
   * @return The selected remux output mode.
   */
  public int getOutputFormat()
  {
    return outputFormat;
  }

  public boolean isClosed()
  {
    return closed;
  }

  /**
   * Close the remuxer instance and block further writing.
   */
  public void close()
  {
    if (closed)
      return;

    if (currentFile != null)
      remuxerMap.remove(currentFile);

    if (remuxer2 != null)
    {
      remuxer2.close();
      remuxer2 = null;

      try
      {
        if (partialTransferIndex > 0)
          writer.write(partialTransfer, 0, partialTransferIndex);

        writer.flush();
      }
      catch (IOException e)
      {}
    }

    closed = true;
  }

  /**
   * Checks the currently buffered write data to see if we can safely split it anywhere.
   * <p/>
   * If the data can be split, the data up to the split will be written out leaving the data that
   * should be written to the new file still in the buffer.
   * <p/>
   * Calling this method also makes the remuxer aware that we are trying to switch and will check
   * the bytes on each write for an opportunity. Once a switching point is detected, all new data
   * will be buffered until switchOutput is called.
   *
   * @param filename The new filename to switch to.
   * @param uploadId The upload ID to be used to authorize the new filename.
   */
  public void startSwitch(String filename, int uploadId)
  {
    synchronized (switchLock)
    {
      // Don't allow SWITCH to the same file.
      if (!switching && !new File(filename).equals(currentFile))
      {
        switchFilename = filename;
        switchUploadId = uploadId;

        switchData = 0;
        switching = true;
        doSwitch();
      }
    }
  }

  public static MediaServerRemuxer getRemuxer(File filename)
  {
    return remuxerMap.get(filename);
  }

  /**
   * Disable internal assistance for this remuxer instance.
   * <p/>
   * This will cause the commands SWITCH and GET_FILE_SIZE to be sent to the network encoder instead
   * of being redirected internally. The only reason you should want to use this is for
   * troubleshooting.
   */
  public void disableInterAssist()
  {
    synchronized (switchLock)
    {
      if (Sage.DBG)
        System.out.println("INFO Network encoder internal assistance has been disabled.");
      if (currentFile != null)
        remuxerMap.remove(currentFile);
      interAssist = false;
    }
  }

  /**
   * Get if switching has completed.
   *
   * @return true if switching has completed.
   */
  public boolean isSwitched()
  {
    synchronized (switchLock)
    {
      return !switching;
    }
  }

  /**
   * Force the the file transition.
   */
  public void forceSwitched()
  {
    synchronized (switchLock)
    {
      if (Sage.DBG) System.out.println("WARNING Forcing transition point!");
      doSwitch(true);
    }
  }

  /**
   * Wait until switching has completed or the current thread has been interrupted.
   *
   * @return true if switching has completed. false may be returned if the thread was interrupted.
   */
  public boolean waitIsSwitched()
  {
    synchronized (switchLock)
    {
      // 30 second timeout.
      int timeout = 60;

      while (switching)
      {
        try
        {
          switchLock.wait(500);
        }
        catch (InterruptedException e)
        {
          break;
        }

        if (timeout-- <= 0)
        {
          if (Sage.DBG)
            System.out.println("WARNING Could not find transition point after over 30 seconds!");
          doSwitch(true);
          break;
        }
      }

      return !switching;
    }
  }

  private void doSwitch()
  {
    doSwitch(false);
  }

  private void doSwitch(boolean force)
  {
    int searchBytes = writeBuffer.position();

    int switchIndex = getSwitchIndex(writeBuffer, 0, searchBytes);
    switchData += searchBytes;

    if (switchIndex == -1)
    {
      if (switchData > SWITCH_BYTES_LIMIT)
      {
        if (Sage.DBG) System.out.println(
            "WARNING Could not find transition point after searching 8 MB of data in stream!");

        switchIndex = 0;
      }
      else if (force)
      {
        switchIndex = 0;
      }
    }

    try
    {
      if (switchIndex != -1)
      {
        synchronized (switchLock)
        {
          if (currentFile != null)
            remuxerMap.remove(currentFile);

          if (switchIndex != 0)
          {
            int oldLimit = writeBuffer.position();

            writeBuffer.position(switchIndex);
            writer.writeFile(writeBuffer);

            writeBuffer.limit(oldLimit).position(switchIndex);
            writeBuffer.compact();
          }

          mediaServer.closeFile(false);
          mediaServer.openWriteFile(switchFilename, switchUploadId, false);

          this.fileChannel = mediaServer.getFileChannel();

          // The thread switching the file is the same thread that writes to the file, so this will
          // not create a race condition.
          fileSize.set(0);

          writer.writeFile(writeBuffer);

          currentFile = new File(switchFilename);
          if (interAssist)
            remuxerMap.put(currentFile, this);

          switching = false;
          switchLock.notifyAll();
        }
      }
      else
      {
        // Write out the current buffer contents so we don't check the same data twice.
        writer.writeFile(writeBuffer);
      }
    }
    catch (IOException e)
    {
      if (Sage.DBG)
      {
        System.out.println("ERROR in MediaServerRemuxer while switching:" + e);
        e.printStackTrace(System.out);
      }
    }
  }

  private int getSwitchIndex(ByteBuffer data, int offset, int length)
  {
    // H.264 and MPEG-2 are the only formats supported.
    if (!h264 && !mpeg2)
    {
      if (Sage.DBG) System.out.println("Forcing fast switch on a non-H264/MPEG2 file!");
      return 0;
    }

    if (outputFormat == MPEGParser2.REMUX_TS)
    {
      if (videoPid == -1)
      {
        if (Sage.DBG) System.out.println("Forcing fast switch without a video PID!");
        return 0;
      }

      int i;
      int tsStart;

      // First we try to locate TS packets
      int endPos = length + offset;
      for (i = offset; i < endPos; i++)
      {
        if (data.get(i) == 0x47 &&
            (i + 188) < endPos && data.get(i + 188) == 0x47 &&
            (i + 188 * 2) < endPos && data.get(i + 188 * 2) == 0x47)
        {

          break;
        }
      }

      int searchLimit = 188 - (h264 ? 7 : 6);
      byte targetHighByte = (byte) (0x40 | ((videoPid >> 8) & 0xFF));
      byte targetLoByte = (byte) (videoPid & 0xFF);

      // Second we find a TS packet with section start and target PID
      while ((i + 188) <= endPos)
      {
        if (data.get(i) == 0x47 &&
            data.get(i + 1) == targetHighByte &&
            data.get(i + 2) == targetLoByte)
        {
          tsStart = i;

          for (int j = 4; j < searchLimit; j++)
          {
            // Verify if that packet contains the magic sequence 00 00 00 01 09 10 00
            // If it does, the data up to the begining of this TS packet go in old file
            // and the new data in the new file

            // NOTE: we could implement faster search but the number of
            // matched packet that reach this point should be quite small...
            if (h264)
            {
              if (data.get(i + j) == 0x00 &&
                  data.get(i + j + 1) == 0x00 &&
                  data.get(i + j + 2) == 0x00 &&
                  data.get(i + j + 3) == 0x01 &&
                  data.get(i + j + 4) == 0x09 &&
                  data.get(i + j + 5) == 0x10 &&
                  data.get(i + j + 6) == 0x00)
              {
                // We have found the vid packet with the magic sequence, write that to old file
                return tsStart;
              }
            }
            else //MPEG-2
            {
              if (data.get(i + j) == 0x00 &&
                  data.get(i + j + 1) == 0x00 &&
                  data.get(i + j + 2) == 0x01 &&
                  data.get(i + j + 3) == 0x00 &&
                  (data.get(i + j + 5) & 0x38) == 0x08) //xx001xxx is I-Frame
              {
                // We have found the vid packet with the magic sequence, write that to old file
                return tsStart;
              }
            }
          }
        }

        i += 188;
      }
    }
    else //MPEG-2 PS
    {
      int b;
      int pos;
      int cur;
      int searchLimit = (length + offset) - 2048;

      int i = 0;
      int psStart;

      while (i <= searchLimit)
      {
        // First locate the 00 00 01 BA block
        if (data.get(i) == 0x00 &&
            data.get(i + 1) == 0x00 &&
            data.get(i + 2) == 0x01 &&
            (data.get(i + 3) & 0xFF) == 0xBA)
        {
          psStart = i;

          pos = 0;
          cur = 0xFFFFFFFF;

          // Populate the first 3 bytes.
          for (; pos < 3; pos++)
          {
            b = data.get(i + pos) & 0xff;
            pos += 1;
            cur <<= 8;
            cur |= b;
          }

          while (pos < 2048)
          {
            b = data.get(i + pos) & 0xff;
            pos += 1;
            cur <<= 8;
            cur |= b;

            if (mpeg2)
            {
              // Video start sequence: 00 00 01 B3
              if ((cur & 0xFFFFFF00) == 0x00000100)
              {
                // Video
                if ((b == 0xB3))
                {
                  return psStart;
                }
              }
            }
            else // H.264 (Just in case?)
            {
              if (cur == 0x00000001)
              {
                if ((i + pos + 3) < 2048)
                {
                  if (data.get(i + pos) == 0x09 &&
                      data.get(i + pos + 1) == 0x10 &&
                      data.get(i + pos + 2) == 0x00)
                  {
                    return psStart;
                  }
                }
              }
            }
          }

          i += 2048;
        }
        else
        {
          i++;
        }
      }
    }

    return -1;
  }

  /**
   * Write to the remuxer.
   *
   * @param data A ByteBuffer with the limit set to the maximum amount of data allowed to be
   *             consumed.
   * @return The number of bytes submitted.
   */
  public int writeRemuxer(ByteBuffer data)
  {
    if (closed) return -1;

    int bytesRead = data.remaining();

    while (data.hasRemaining())
    {
      // A null ContainerFormat means the stream is still being initialized.
      if (containerFormat == null)
      {
        int writeLimit = Math.min(transferBuffer.length - initOffset, data.remaining());

        data.get(transferBuffer, initOffset, writeLimit);
        pushData(transferBuffer, initOffset, writeLimit);
        containerFormat = remuxer2.getContainerFormat();

        initOffset += writeLimit;

        if (containerFormat != null)
        {
          // Reset the partial transfer index or the very first write will be out of sync.
          partialTransferIndex = 0;
          VideoFormat videoFormat = remuxer2.getContainerFormat().getVideoFormat();

          if (videoFormat != null)
          {
            h264 = videoFormat.getFormatName().equals(MediaFormat.H264);
            mpeg2 = videoFormat.getFormatName().equals(MediaFormat.MPEG2_VIDEO);
            videoPid = Integer.parseInt(videoFormat.getId(), 16);
          }
          else
          {
            if (Sage.DBG) System.out.println("Video format does not exist." +
                " Transition points will not be able to be determined.");
          }
        }
        else if (initOffset == transferBuffer.length)
        {
          if (transferBuffer.length < MAX_INIT_BUFFER_SIZE)
          {
            byte[] newBuffer = new byte[Math.min(transferBuffer.length * 2, MAX_INIT_BUFFER_SIZE)];
            System.arraycopy(transferBuffer, 0, newBuffer, 0, transferBuffer.length);
            transferBuffer = newBuffer;

            if (Sage.DBG) System.out.println("Container format not detected," +
                " expanding buffer. transferBuffer=" + transferBuffer.length);
          }
          else if (transferBuffer.length == MAX_INIT_BUFFER_SIZE)
          {
            // Reset the buffer and keep trying until the file is closed.
            initOffset = 0;

            // Change to auto-detect the channel in case the selection is wrong.
            remuxer2.close();
            tuneStringType = MPEGParser2.TuneStringType.CHANNEL;
            channel = 0;
            tsid = 0;
            data1 = 0;
            data2 = 0;
            data3 = 0;
            remuxer2 = MPEGParser2.openRemuxer(inputFormat, outputFormat, streamFormat, subFormat, tuneStringType, channel, tsid, data1, data2, data3, writer);

            if (Sage.DBG) System.out.println("Container format not detected," +
                " clearing buffer, setting channel to 0." +
                " transferBuffer=" + transferBuffer.length);
          }
        }

        if (containerFormat != null)
        {
          // Change over to TS if the video is anything other than MPEG2.

          VideoFormat videoFormat = containerFormat.getVideoFormat();
          String formatName = videoFormat != null ? videoFormat.getFormatName() : null;

          if (formatName != null &&
              outputFormat == MPEGParser2.REMUX_PS &&
              !formatName.equals(MediaFormat.MPEG1_VIDEO) &&
              !formatName.equals(MediaFormat.MPEG2_VIDEO))
          {
            remuxer2.close();
            outputFormat = MPEGParser2.REMUX_TS;
            remuxer2 = MPEGParser2.openRemuxer(inputFormat, outputFormat, streamFormat, subFormat, tuneStringType, channel, tsid, data1, data2, data3, writer);

            // This really shouldn't be happening.
            if (remuxer2 == null)
            {
              return -1;
            }

            int offset = 0;
            int length;

            // We can't transfer too much at a time.
            while (offset < initOffset)
            {
              length = Math.min(MAX_TRANSFER, initOffset - offset);

              if (remuxer2.pushInitData(transferBuffer, offset, length))
                break;

              offset += length;
            }

            containerFormat = remuxer2.getContainerFormat();

            // In case somehow the exact same amount of data doesn't result in the stream being
            // re-detected.
            if (containerFormat == null)
            {
              continue;
            }
          }

          pushData(transferBuffer, 0, initOffset);

          // We no longer need a large buffer. 64k is the largest amount of data that should come in
          // at one time from the media server.
          transferBuffer = new byte[65536];

          // This keeps an empty buffer from reaching the main loop.
          if (!data.hasRemaining())
          {
            break;
          }
        }
      }

      if (containerFormat != null)
      {
        int totalLength = data.remaining();
        int bufferOffset = 0;
        byte currentBuffer[];

        if (transferBuffer == null || data.remaining() > transferBuffer.length)
        {
          transferBuffer = new byte[data.remaining() * 2];
        }

        data.get(transferBuffer, 0, totalLength);
        currentBuffer = transferBuffer;

        pushData(currentBuffer, bufferOffset, totalLength);
      }
    }

    return bytesRead;
  }

  // This is the only method that should be used internally to push new data into the remuxer.
  private void pushData(byte[] buffer, int offset, int length)
  {
    int transferLength;
    int limit = offset + length;

    while (offset < limit)
    {
      transferLength = Math.min(MAX_TRANSFER, limit - offset);

      // Not enough data to fill the transfer buffer.
      if (transferLength < partialTransfer.length - partialTransferIndex)
      {
        System.arraycopy(buffer, offset, partialTransfer, partialTransferIndex, transferLength);
        partialTransferIndex += transferLength;
        break;
      }

      if (partialTransferIndex > 0)
      {
        // Fill the rest of the partial packet from the start of the current transfer.
        transferLength = partialTransfer.length - partialTransferIndex;
        System.arraycopy(buffer, offset, partialTransfer, partialTransferIndex, transferLength);

        int extraBytes = 0;
        int partialTransferOffset = 0;
        int partialTransferLength = partialTransfer.length;

        // If we are out of sync, try to fixed it. This should not be happening at all, but it is
        // possible and the network encoder may not know it happened.
        if (partialTransfer[partialTransferOffset] != 0x47 || !tsSynced)
        {
          int startingOffset = partialTransferOffset;
          if (Sage.DBG && tsSynced) System.out.println("Remuxer is buffering out of sync.");

          while (partialTransferLength - (partialTransferOffset + 1 + 188 * 2) > 0)
          {
            partialTransferOffset++;

            tsSynced = buffer[partialTransferOffset] == 0x47 &&
                buffer[partialTransferOffset + TS_ALIGN] == 0x47 &&
                buffer[partialTransferOffset + TS_ALIGN * 2] == 0x47;

            if (tsSynced)
              break;
          }

          if (!tsSynced)
          {
            if (Sage.DBG) System.out.println(
                "Remuxer cannot find sync byte after checking " + (offset - startingOffset) + " bytes");

            // Drop the data. This will endlessly loop otherwise.
            partialTransferIndex = 0;
            return;
          }

          // The available length has changed.
          partialTransferLength = partialTransferLength - partialTransferOffset;
          extraBytes = partialTransferLength % TS_ALIGN;
          partialTransferLength -= extraBytes;
        }

        if (containerFormat != null)
          remuxer2.pushRemuxData(partialTransfer, partialTransferOffset, partialTransferLength);
        else
          remuxer2.pushInitData(partialTransfer, partialTransferOffset, partialTransferLength);

        if (extraBytes > 0)
        {
          System.arraycopy(partialTransfer, partialTransferOffset + partialTransferLength, partialTransfer, 0, extraBytes);
          partialTransferIndex = extraBytes;
        }
        else
        {
          partialTransferIndex = 0;
        }

        offset += transferLength;
        continue;
      }

      // If we are out of sync, try to fixed it. This should not be happening at all, but it is
      // possible and the network encoder may not know it happened.
      if (buffer[offset] != 0x47 || !tsSynced)
      {
        int startingOffset = offset;
        if (Sage.DBG && tsSynced) System.out.println("Remuxer is out of sync.");

        while (length - (offset + 1 + 188 * 2) > 0)
        {
          offset++;

          tsSynced = buffer[offset] == 0x47 &&
              buffer[offset + TS_ALIGN] == 0x47 &&
              buffer[offset + TS_ALIGN * 2] == 0x47;

          if (tsSynced)
            break;
        }

        if (!tsSynced && Sage.DBG) System.out.println(
            "Remuxer cannot find sync byte after checking " + (offset - startingOffset) + " bytes");

        // The available length has changed.
        continue;
      }

      if (containerFormat != null)
        remuxer2.pushRemuxData(buffer, offset, transferLength);
      else
        remuxer2.pushInitData(buffer, offset, transferLength);

      offset += transferLength;
    }
  }

  // This prevents the write methods from being available to anything but the remuxer.
  public class RemuxWriter extends OutputStream
  {
    @Override
    public void write(byte[] b, int off, int len) throws IOException
    {
      // Don't write anything if the stream is not detected since the detection data will rewind.
      if (containerFormat == null)
      {
        return;
      }

      if (writeBuffer.remaining() < len)
      {
        synchronized (switchLock)
        {
          if (switching)
          {
            // This method will write out the "searched" data before it returns.
            doSwitch();
          }
          else
          {
            writeFile(writeBuffer);
          }
        }

        if (writeBuffer.remaining() < len)
        {
          writeBuffer = ByteBuffer.allocateDirect(len);
        }
      }

      writeBuffer.put(b, off, len);
    }

    byte moveByte[];

    @Override
    public void write(int b) throws IOException
    {
      // This method should not be used, but this will prevent that from causing data loss.
      if (moveByte == null)
      {
        moveByte = new byte[1];
      }

      moveByte[0] = (byte) b;

      write(moveByte, 0, 1);
    }

    protected void writeFile(ByteBuffer data) throws IOException
    {
      data.flip();

      if (bufferLimit > 0 && (bufferIndex += data.remaining()) >= bufferLimit)
      {
        int overLimit = (int) (bufferIndex - bufferLimit);

        if (overLimit > 0)
        {
          int oldLimit = data.limit();
          data.limit(overLimit);

          while (data.hasRemaining())
          {
            fileSize.addAndGet(fileChannel.write(data));
          }

          data.limit(oldLimit);
        }

        bufferIndex = 0;
        fileChannel.position(0);
      }

      while (data.hasRemaining())
      {
        fileSize.addAndGet(fileChannel.write(data));
      }

      data.clear();
    }

    @Override
    public void flush() throws IOException
    {
      writeFile(writeBuffer);
    }
  }
}
