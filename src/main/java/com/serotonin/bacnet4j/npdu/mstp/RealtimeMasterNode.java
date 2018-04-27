/**
 * Copyright (C) 2018 Infinite Automation Software. All rights reserved.
 */
package com.serotonin.bacnet4j.npdu.mstp;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Properties;

import org.apache.commons.lang3.StringUtils;

import com.serotonin.bacnet4j.npdu.mstp.realtime.RealtimeDriver;
import com.serotonin.bacnet4j.transport.Transport;
import com.serotonin.bacnet4j.util.sero.StreamUtils;


/**
 * MS/TP Master node using a real-time serial driver 
 *   installed in the linux Kernel.
 * 
 * @author Terry Packer
 */
public class RealtimeMasterNode extends MasterNode {
    
    private int portFileDescriptor = -1;
    private final byte thisStation;
    private final RealtimeDriver driver;
    private final int baud;
    private final boolean useJNA;
    private int responseTimeoutMs = 1000; //TODO Make configurable
    private long lastFrameSendTime; //Track response timeouts
    
    public RealtimeMasterNode(final String portId, final Properties driverProperties, final byte thisStation, 
            final int retryCount, final int baud, boolean useJNA) throws IllegalArgumentException {
        super(portId, null, null, (byte)0xFF, retryCount);
        this.thisStation = thisStation;
        this.baud = baud;
        this.useJNA = useJNA;
        this.driver = new RealtimeDriver(driverProperties, useJNA);
        if(driverProperties.containsKey("RESPONSE_TIMEOUT_MS"))
            responseTimeoutMs = Integer.parseInt(driverProperties.getProperty("RESPONSE_TIMEOUT_MS"));
    }
    
    @Override
    protected void validate(final int retryCount) {
        this.retryCount = retryCount;
        nextStation = thisStation;
        pollStation = thisStation;
        tokenCount = Constants.POLL;
        soleMaster = false;
        state = MasterNodeState.idle;
    }
    
    @Override
    public void setMaxMaster(final int maxMaster) {
        super.setMaxMaster(maxMaster);
        if(portFileDescriptor > 0)
            this.driver.setMaxMaster(portFileDescriptor, (byte)maxMaster);
    }

    @Override
    public void setMaxInfoFrames(final int maxInfoFrames) {
        super.setMaxInfoFrames(maxInfoFrames);
        if(portFileDescriptor > 0)
            this.driver.setMaxInfoFrames(portFileDescriptor, (byte)maxInfoFrames);
    }

    @Override
    public void setUsageTimeout(final int usageTimeout) {
        super.setUsageTimeout(usageTimeout);
        if(portFileDescriptor > 0)
            this.driver.setTUsage(portFileDescriptor, (byte)usageTimeout);
    }
    
    @Override
    public void initialize(final Transport transport) throws Exception {
        if(useJNA) {
          portFileDescriptor = driver.setupPort(portId, baud);
          driver.setMac(portFileDescriptor, thisStation);
          driver.setMaxMaster(portFileDescriptor, (byte)this.maxMaster);
          driver.setTUsage(portFileDescriptor, (byte)this.usageTimeout);
          driver.setMaxInfoFrames(portFileDescriptor, (byte)this.maxInfoFrames);
          in = new RealtimeDriverInputStream(driver, portFileDescriptor);
          out = new RealtimeDriverOutputStream(driver, portFileDescriptor);
        }else {
            //Setup I/O
            File file = new File(portId);
            in = new FileInputStream(file);
            out = new FileOutputStream(file);
    
            //Configure Driver
            this.driver.configure(portId, baud, thisStation, maxMaster, maxInfoFrames, usageTimeout);
        }

        super.initialize(transport);
    }
    
    @Override
    protected void doCycle() {
        readFrame();
        
        if (state == MasterNodeState.idle)
            idle();
        
        if (state == MasterNodeState.useToken)
            useToken();
        
        if(state == MasterNodeState.doneWithToken)
            state = MasterNodeState.idle;
        
        if (state == MasterNodeState.waitForReply)
            waitForReply();
        
        //TODO Can't currently get to this state since we don't have 
        // the frame type from the driver so we have to do this every time
        //if (state == MasterNodeState.answerDataRequest)
        answerDataRequest();

        //TODO Remove when done debugging
        //try {Thread.sleep(500);}catch(InterruptedException e) {}
    }

    /* (non-Javadoc)
     * @see com.serotonin.bacnet4j.npdu.mstp.MstpNode#readFrame()
     */
    @Override
    protected void readFrame() {
        readInputStream();
        if (receiveError) {
            // EatAnError
            receiveError = false;
            eventCount++;
            activity = true;
        }
    }
    
    @Override
    protected void readInputStream() {
        try {
            //Read 1 message from the driver
            LOG.info("About to read.");
            while((readCount = in.read(readArray)) > 0) {
                
            //readCount = in.read(readArray);
            LOG.info("Read: " + StreamUtils.dumpArrayHex(readArray, 0, readCount));
            //if(readCount > 0) {
                bytesIn += readCount;
                if (LOG.isTraceEnabled())
                    LOG.trace(tracePrefix() + "in: " + StreamUtils.dumpArrayHex(readArray, 0, readCount));
                
                int pos = 0;
                //frame.setFrameType(FrameType.bacnetDataNotExpectingReply);
                frame.setSourceAddress(readArray[pos++]);
                byte[] data = new byte[readCount - 1];
                for(int i=0; i<readCount - 1; i++) {
                    data[i] = readArray[pos++];
                }
                frame.setData(data);
                if (LOG.isTraceEnabled())
                    LOG.trace("in: " + frame);
                eventCount += readCount;
                receivedValidFrame = true;
            }
        } catch (final IOException e) {
            if (StringUtils.equals(e.getMessage(), "Stream closed."))
                throw new RuntimeException(e);
            if (LOG.isDebugEnabled())
                LOG.debug(thisStation + " Input stream listener exception", e);
            receiveError = true;
        }
    }
    
    @Override
    protected void idle() {
        //Don't worry about invalid frames, assume we can use token if we didn't get a frame
        if (receivedValidFrame) {
            if (LOG.isDebugEnabled())
                LOG.debug(thisStation + " idle:receivedValidFrame");
            frame();
            receivedValidFrame = false;
            activity = true;
        }else {
            //We can use the token
            state = MasterNodeState.useToken;
        }
    }
    
    /* (non-Javadoc)
     * @see com.serotonin.bacnet4j.npdu.mstp.MasterNode#frame()
     */
    @Override
    protected void frame() {
        receivedDataNoReply(frame);
        
        //TODO How to decide?  via NPDU or do we modify the driver
        //state = MasterNodeState.answerDataRequest;
        //replyDeadline = lastNonSilence + Constants.REPLY_DELAY;
    }
    
    @Override
    protected void waitForReply() {
        if(lastFrameSendTime + responseTimeoutMs > clock.millis()) {
            if (LOG.isDebugEnabled())
                LOG.debug(thisStation + " waitForReply:ReplyTimeout");
            state = MasterNodeState.idle;
        }else if (receivedValidFrame) {
            if (LOG.isDebugEnabled())
                LOG.debug(thisStation + " waitForReply:ReceivedReply");
            receivedDataNoReply(frame);
            state = MasterNodeState.idle;
            receivedValidFrame = false;
        }
    }
    
    /* (non-Javadoc)
     * @see com.serotonin.bacnet4j.npdu.mstp.MasterNode#answerDataRequest()
     */
    @Override
    protected void answerDataRequest() {
        synchronized (this) {
            if (replyFrame != null) {
                // Reply
                if (LOG.isDebugEnabled())
                    LOG.debug(thisStation + " answerDataRequest:Reply");
                sendFrame(replyFrame);
                replyFrame = null;
                state = MasterNodeState.idle;
                activity = true;
            }
        }
    }
    
    
    @Override
    protected void sendFrame(final Frame frame) {
        LOG.info("Sending frame: " + frame);
        try {
            if (LOG.isTraceEnabled())
                LOG.trace(tracePrefix() + "out: " + frame);
            
            // Header
            byte[] writeArray = new byte[5 + frame.getLength()];
            int pos = 0;
            writeArray[pos++] = frame.getFrameType().id;
            writeArray[pos++] = frame.getDestinationAddress();
            writeArray[pos++] = frame.getSourceAddress();
            writeArray[pos++] = (byte)(frame.getLength() >> 8 & 0xff);
            writeArray[pos++] = (byte)(frame.getLength() & 0xff);
            
           // out.write(frame.getFrameType().id & 0xff);
           // out.write(frame.getDestinationAddress() & 0xff);
           // out.write(frame.getSourceAddress() & 0xff);
           // out.write(frame.getLength() >> 8 & 0xff);
           // out.write(frame.getLength() & 0xff);
            //Driver doesn't want CRC out.write(sendHeaderCRC.getCrc(frame));
            bytesOut += 8;

            if (frame.getLength() > 0) {
                // Data
                //out.write(frame.getData());
                for(int i=0; i<frame.getLength(); i++)
                    writeArray[pos++] = frame.getData()[i];
                //No CRC with Driver
                //final int crc = sendDataCRC.getCrc(frame);
                //out.write(crc & 0xff);
                //out.write(crc >> 8 & 0xff);
                bytesOut += frame.getLength(); // + 2;
            }
            out.write(writeArray);
            out.flush();
            lastFrameSendTime = clock.millis();
            LOG.info("Sent frame " + frame);
        } catch (final IOException e) {
            // Only write the same error message once. Prevents logs from getting filled up unnecessarily with repeated
            // error messages.
            if (!StringUtils.equals(e.getMessage(), lastWriteError)) {
                // NOTE: should anything else be informed of this?
                LOG.error("Error while sending frame", e);
                lastWriteError = e.getMessage();
            }
        }
    }
    
    @Override
    public void terminate() {
        super.terminate();
        try {
            in.close();
            out.close();
        } catch (IOException e) {
            LOG.error("Error closing streams.", e);
        }
//        if(portFileDescriptor > 0) {
//            this.driver.close(portFileDescriptor);
//        }
    }
    
    public static class RealtimeDriverInputStream extends InputStream {
        
        private final RealtimeDriver driver;
        private final int handle;
        private final ArrayList<byte[]> history;
        
        public RealtimeDriverInputStream(RealtimeDriver driver, int handle) {
            this.driver = driver;
            this.handle = handle;
            this.history = new ArrayList<>();
        }

        
        @Override
        public int read() throws IOException {
            //TODO This doesn't work well with the driver
            throw new RuntimeException("Unsupported read()");
//            byte[] inBuffer = new byte[1];
//            while (true) {
//                int read = driver.read(handle, inBuffer, 1);
//                if (read < 1) {
//                    ThreadUtils.sleep(20);
//                    continue;
//                }
//                return inBuffer[0];
//            }
        }
        
        /* (non-Javadoc)
         * @see java.io.InputStream#read(byte[])
         */
        @Override
        public int read(byte[] b) throws IOException {
            history.add(b);
            return driver.read(handle, b, b.length);
        }
        
        @Override
        public int read(final byte[] b, final int off, final int len) throws IOException {
            throw new RuntimeException("Unsupported read(final byte[] b, final int off, final int len)");
//            if (len == 0) {
//                return 0;
//            }
//
//            final byte[] buf = new byte[len];
//            int length = driver.read(handle, buf, len);
//            if(length > 0) {
//                System.arraycopy(buf, 0, b, off, length);
//                System.out.println("Recieved: " );
//                for(int i=0; i<length; i++)
//                    System.out.print(String.format("0x%02X", buf[i]) + " ");
//                System.out.println();
//            }
//            return length;
        }
        
        @Override
        public int available() throws IOException {
            throw new IOException("available(): Unsupported operation.");
        }
    }
    
    public static class RealtimeDriverOutputStream extends OutputStream {
        
        private final RealtimeDriver driver;
        private final int handle;
        private final ArrayList<byte[]> history;
        
        public RealtimeDriverOutputStream(RealtimeDriver driver, int handle) {
            this.driver = driver;
            this.handle = handle;
            this.history = new ArrayList<>();
        }

        @Override
        public void write(final int b) throws IOException {
            throw new RuntimeException("Unsupported write(b)");
            //driver.write(handle, new byte[] {(byte)b}, 1);
        }

        @Override
        public void write(final byte[] buffer) throws IOException {
            driver.write(handle, buffer, buffer.length);
            history.add(buffer);
        }
        /* (non-Javadoc)
         * @see java.io.OutputStream#flush()
         */
        @Override
        public void flush() throws IOException {
            driver.flush(handle);
        }
    }
}
