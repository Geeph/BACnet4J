/**
 * Copyright (C) 2018 Infinite Automation Software. All rights reserved.
 */
package com.serotonin.bacnet4j.npdu.mstp.realtime;

import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sun.jna.LastErrorException;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.NativeLong;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;

/**
 * IOCTL JNA Wrapper To handle IOCTL calls to proprietary 
 * Realtime MS/TP Driver
 * 
 * @author Terry Packer
 */
public class RealtimeDriver {
    static final Logger LOG = LoggerFactory.getLogger(RealtimeDriver.class);
    
    static ClibDirectMapping clib;
    
    static {
        Native.setProtected(true);
        if(!Native.isProtected())
            LOG.warn("Running in un-procted mode because the JVM's lack of protected JNA support.");
        Native.loadLibrary(Platform.C_LIBRARY_NAME, Clib.class);
        Native.register(ClibDirectMapping.class, NativeLibrary.getInstance(Platform.C_LIBRARY_NAME));
        clib = new ClibDirectMapping();
    }
    
    public RealtimeDriver(Properties driverConstants) {
        //Load in the constants and validate they are all there.
        N_MSTP = ensureConstant("N_MSTP", driverConstants);
        MSTP_IOC_SETMACADDRESS = ensureConstant("MSTP_IOC_SETMACADDRESS", driverConstants);
        MSTP_IOC_GETMACADDRESS = ensureConstant("MSTP_IOC_GETMACADDRESS", driverConstants);
        F_SETFL = ensureConstant("F_SETFL", driverConstants);
        O_NONBLOCK = ensureConstant("O_NONBLOCK", driverConstants);
        O_RDWR = ensureConstant("O_RDWR", driverConstants);
        FNDELAY = ensureConstant("FNDELAY", driverConstants);
        CLOCAL = ensureConstant("CLOCAL", driverConstants);
        HUPCL = ensureConstant("HUPCL", driverConstants);
        CS8 = ensureConstant("CS8", driverConstants);
        CSTOPB = ensureConstant("CSTOPB", driverConstants);
        CREAD = ensureConstant("CREAD", driverConstants);
        PARENB = ensureConstant("PARENB", driverConstants);
        PARODD = ensureConstant("PARODD", driverConstants);
        ICANON = ensureConstant("ICANON", driverConstants);
        ECHO = ensureConstant("ECHO", driverConstants);
        ECHOE = ensureConstant("ECHOE", driverConstants);
        ISIG = ensureConstant("ISIG", driverConstants);
        IGNBRK = ensureConstant("IGNBRK", driverConstants);
        IGNPAR = ensureConstant("IGNPAR", driverConstants);
        VMIN = ensureConstant("VMIN", driverConstants);
        VTIME = ensureConstant("VTIME", driverConstants);
        TCIFLUSH = ensureConstant("TCIFLUSH", driverConstants);
        TCSANOW = ensureConstant("TCSANOW", driverConstants);
        ASYNC_LOW_LATENCY = ensureConstant("ASYNC_LOW_LATENCY", driverConstants);
        TIOCSETSD = ensureConstant("TIOCSETSD", driverConstants);
        TIOCGSERIAL = ensureConstant("TIOCGSERIAL", driverConstants);
        TIOCSSERIAL = ensureConstant("TIOCSSERIAL", driverConstants);
        FIONREAD = ensureConstant("FIONREAD", driverConstants);
        B38400 = ensureConstant("B38400", driverConstants);
    }
    
    private static int ensureConstant(String name, Properties constants) {
        String value = constants.getProperty(name);
        if(value == null)
            throw new IllegalArgumentException("Missing realtime driver constant " + name);
        try {
            if(value.startsWith("0x") || value.startsWith("0X")) {
                return Integer.parseUnsignedInt(value.substring(2), 16);
            }
            return Integer.parseInt(value);
        }catch(NumberFormatException e) {
            throw new IllegalArgumentException("Invalid format for realtime driver constant " + name + ". " + e.getMessage());
        }
    }
    
    /**
     * Set the mac address for the driver
     * @param fd
     * @param mac
     * @throws LastErrorException
     */
    public void setMac(int fd, byte mac) throws LastErrorException{
        clib.ioctl(fd, MSTP_IOC_SETMACADDRESS, mac);
    }
    
    public int setupPort(String port, int baud) {
        int fd = clib.open(port, (O_RDWR | O_NONBLOCK));
        
        this.setupPort(fd, baud);
        return fd;
    }
    
    /**
     * Configure the port via it's file descriptor
     * to use the driver.
     * 
     * @param portFileDescriptor
     */
    public void setupPort(int fd, int baud) {
        clib.fcntl(fd, F_SETFL, FNDELAY);
        
        //Get the line discipline
        Termios termios = new Termios();
        System.out.println("Getting Termios Struct");
        clib.tcgetattr(fd, termios);

        termios.c_cflag = CREAD | CLOCAL;
        
        //set baud rate
        //TODO this is a long in the c code
        termios.c_cflag |= baud;
        
        //clear the HUPCL bit, close doesn't change DTR
        termios.c_cflag &= ~HUPCL;

        //setup for 8-N-1
        termios.c_cflag |= CS8;                           //8 databits
        termios.c_cflag &= ~(PARENB | PARODD);            //No parity
        termios.c_cflag &= ~CSTOPB;                       //1 stop bit    
        /* set input flag non-canonical, no processing */
        termios.c_lflag &= ~(ICANON | ECHO | ECHOE | ISIG);
        
        /* ignore parity errors */
        termios.c_iflag = (IGNBRK | IGNPAR);

        /* set output flag non-canonical, no processing */
        termios.c_oflag = 0;

        termios.c_cc[VTIME] = 0;   /* no time delay */
        termios.c_cc[VMIN]  = 1;   /* no char delay */
        
        //Set the line discipline
        termios.c_line = (byte)N_MSTP;
        
        /* flush the buffer */
        clib.tcflush(fd, TCIFLUSH);
            
        //Set termios struct
        clib.tcsetattr(fd, TCSANOW, termios);
            
        SerialStruct serialInfo = new SerialStruct();
        //Get  Serial Info
        clib.ioctl(fd, TIOCGSERIAL, serialInfo);
        
        serialInfo.flags |= ASYNC_LOW_LATENCY;
        //Set Low Latency Flag
        clib.ioctl(fd, TIOCSSERIAL, serialInfo);
        
        //Switch to MSTP line discipline
        clib.ioctlJava(fd, TIOCSETSD, N_MSTP);
    }
    
    /**
     * Read from IOCTL
     * @param fd
     * @param cmd
     * @return
     */
    public int ioctlRead(int handle, int cmd) {
        int[] data = new int[1];
        clib.ioctl(handle, cmd, data);
        return data[0];
    }
    
//    public int available(int handle) {
//        //TODO this doesn't work with the driver I don't think.
//        int[] data = new int[1];
//        clib.ioctlJava(handle, FIONREAD, data);
//        return data[0];
//    }
    public int read(int handle, byte[] buffer, int length) {
        return clib.read(handle, buffer, length);
    }
    public int write(int handle, byte[] buffer, int count) {
        return clib.write(handle, buffer, count);
    }
    
    public void close(int handle) {
        clib.close(handle);
    }
    
    public void flush(int handle) {
        clib.tcflush(handle, TCIFLUSH);
    }
    
    private final int N_MSTP;
    private final int MSTP_IOC_SETMACADDRESS;
    private final int MSTP_IOC_GETMACADDRESS;
    private final int F_SETFL;
    private final int O_NONBLOCK;
    private final int O_RDWR;
    private final int FNDELAY;
    private final int CLOCAL;
    private final int HUPCL;
    private final int CS8;
    private final int CSTOPB;
    private final int CREAD;
    private final int PARENB;
    private final int PARODD;
    private final int ICANON;
    private final int ECHO;
    private final int ECHOE;
    private final int ISIG;
    private final int IGNBRK;
    private final int IGNPAR;
    private final int VMIN;
    private final int VTIME;
    private final int TCIFLUSH;
    private final int TCSANOW;
    private final int ASYNC_LOW_LATENCY;
    private final int TIOCSETSD;
    private final int TIOCGSERIAL;
    private final int TIOCSSERIAL;
    private final int FIONREAD;
    private final int B38400;
    
    public static class ClibDirectMapping implements Clib {
        @Override
        native public int fcntl(int fd, int cmd, int arg) throws LastErrorException;
        @Override
        native public int open(String path, int flags) throws LastErrorException;
        @Override
        native public int tcflush(int fd, int qs) throws LastErrorException;
        @Override
        native public int close(int fd) throws LastErrorException;
        @Override
        native public int write(int fd, byte[] buffer, int count);
        @Override
        native public int read(int fd, byte[] buffer, int count);
        @Override
        native public int ioctl(int fd, int cmd, byte arg) throws LastErrorException;
        @Override
        native public int ioctl(int fd, int cmd, int[] arg) throws LastErrorException;
        @Override
        native public int ioctl(int fd, int cmd, SerialStruct arg) throws LastErrorException;
        @Override
        native public int tcgetattr(int fd, Termios termios) throws LastErrorException;
        @Override
        native public int tcsetattr(int fd, int cmd, Termios termios) throws LastErrorException;
        
        public int ioctlJava(int fd, int cmd, int... arg) {
            return ioctl(fd, cmd, arg);
        }

    }

   interface Clib extends Library {

       public int fcntl(int fd, int cmd, int arg) throws LastErrorException;
       public int ioctl(int fd, int cmd, int[] arg) throws LastErrorException;
       public int open(String path, int flags) throws LastErrorException;
       public int tcflush(int fd, int qs) throws LastErrorException;
       public int close(int fd) throws LastErrorException; 
       public int write(int fd, byte[] buffer, int count);
       public int read(int fd, byte[] buffer, int count);
       public int ioctl(int fd, int cmd, byte arg) throws LastErrorException;
       public int ioctl(int fd, int cmd, SerialStruct arg) throws LastErrorException;
       public int tcgetattr(int fd, Termios termios) throws LastErrorException;
       public int tcsetattr(int fd, int cmd, Termios termios) throws LastErrorException;
       
    }

   /**
    * Map over termios memory
    *
    * @author Terry Packer
    */
   public static class Termios extends Structure {

       public int c_iflag;
       public int c_oflag;
       public int c_cflag;
       public int c_lflag;
       public byte c_line;
       public byte[] c_cc = new byte[32];
       public int c_ispeed;
       public int c_ospeed;

       @Override
       protected List<String> getFieldOrder() {
           return Arrays.asList(//
                   "c_iflag",//
                   "c_oflag",//
                   "c_cflag",//
                   "c_lflag",//
                   "c_line",//
                   "c_cc",//
                   "c_ispeed",//
                   "c_ospeed"//
           );
       }

   }

   /**
    * Map over Serial Struct
    *
    * @author Terry Packer
    */
   public static class SerialStruct extends Structure {

       public int type;
       public int line;
       public int port;
       public int irq;
       public int flags;
       public int xmit_fifo_size;
       public int custom_divisor;
       public int baud_base;
       public short close_delay;
       public short io_type;
       //public char io_type;
       //public char reserved_char;
       public int hub6;
       public short closing_wait;
       public short closing_wait2;
       public Pointer iomem_base;
       public short iomem_reg_shift;
       public int port_high;
       public NativeLong iomap_base;

       @Override
       protected List<String> getFieldOrder() {
           return Arrays.asList(//
                   "type",//
                   "line",//
                   "port",//
                   "irq",//
                   "flags",//
                   "xmit_fifo_size",//
                   "custom_divisor",//
                   "baud_base",//
                   "close_delay",//
                   "io_type",//
                   //public char io_type;
                   //public char reserved_char;
                   "hub6",//
                   "closing_wait",//
                   "closing_wait2",//
                   "iomem_base",//
                   "iomem_reg_shift",//
                   "port_high",//
                   "iomap_base"//
           );
       }
   }
}
