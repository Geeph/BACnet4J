/**
 * Copyright (C) 2018 Infinite Automation Software. All rights reserved.
 */
package com.serotonin.bacnet4j.npdu.mstp;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Properties;

import org.apache.commons.lang3.StringUtils;

import com.serotonin.bacnet4j.transport.Transport;


/**
 *
 * @author Terry Packer
 */
public class RealtimeMasterNode extends MasterNode {
    
    private final int portFileDescriptor;
    private final byte thisStation;
    private final RealtimeDriver driver;
    
    private ReadFrameState readFrameState;

    
    public RealtimeMasterNode(final String portId, final int portFileDescriptor, final InputStream in, 
            final OutputStream out, final Properties driverProperties, final byte thisStation, final int retryCount) throws IllegalArgumentException {
        super(portId, in, out, (byte)0xFF, retryCount);
        this.portFileDescriptor = portFileDescriptor;
        this.thisStation = thisStation;
        this.driver = new RealtimeDriver(driverProperties);
    }
    
    @Override
    public void setMaxMaster(final int maxMaster) {
        super.setMaxMaster(maxMaster);
        //TODO ioctl to set max master
    }

    @Override
    public void setMaxInfoFrames(final int maxInfoFrames) {
        super.setMaxInfoFrames(maxInfoFrames);
        //TODO ioctl to set usage timeout
    }

    @Override
    public void setUsageTimeout(final int usageTimeout) {
        super.setUsageTimeout(usageTimeout);
        //TODO ioctl to set usage timeout
    }
    
    @Override
    public void initialize(final Transport transport) throws Exception {
        super.initialize(transport);
        readFrameState = ReadFrameState.idle;
        driver.setupPort(portFileDescriptor);
        driver.setMac(portFileDescriptor, thisStation);
    }
    
    @Override
    protected void doCycle() {
        readFrame();

        if (state == MasterNodeState.idle)
            idle();

        if (state == MasterNodeState.useToken)
            useToken();

        if (state == MasterNodeState.waitForReply)
            waitForReply();

        if (state == MasterNodeState.answerDataRequest)
            answerDataRequest();
    }
    
    @Override
    protected void readFrame() {
        readInputStream();
        
        if (receiveError) {
            // EatAnError
            receiveError = false;
            eventCount++;
            readFrameState = ReadFrameState.idle;
            activity = true;
        }
        
        if (!receivedValidFrame) {
            if (readFrameState == ReadFrameState.idle)
                readSourceAddress();

            if (readFrameState == ReadFrameState.data)
                data();

            if (readFrameState == ReadFrameState.dataCrc)
                dataCrc();
        }
    }
    
    private void readSourceAddress() {
        if(inputBuffer.size() > 0) {
            frame.setSourceAddress(inputBuffer.pop());
            readFrameState = ReadFrameState.data;
        }
    }
    
    @Override
    protected void idle() {
        if (receivedInvalidFrame != null) {
            // ReceivedInvalidFrame
            if (LOG.isDebugEnabled())
                LOG.debug(thisStation + " idle:Received invalid frame: " + receivedInvalidFrame);
            receivedInvalidFrame = null;
            activity = true;
        } else if (receivedValidFrame) {
            frame();
            receivedValidFrame = false;
            activity = true;
        }
    }
    
    @Override
    protected void sendFrame(final Frame frame) {
        try {
            if (LOG.isTraceEnabled())
                LOG.trace(tracePrefix() + "out: " + frame);
            
            // Header
            out.write(frame.getFrameType().id & 0xff);
            out.write(frame.getDestinationAddress() & 0xff);
            out.write(frame.getSourceAddress() & 0xff);
            out.write(frame.getLength() >> 8 & 0xff);
            out.write(frame.getLength() & 0xff);
            out.write(sendHeaderCRC.getCrc(frame));
            bytesOut += 8;

            if (frame.getLength() > 0) {
                // Data
                out.write(frame.getData());
                final int crc = sendDataCRC.getCrc(frame);
                out.write(crc & 0xff);
                out.write(crc >> 8 & 0xff);
                bytesOut += frame.getLength() + 2;
            }

            out.flush();
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
    
}
