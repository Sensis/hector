package me.prettyprint.cassandra.utils;

import org.apache.thrift.TException;

import java.net.SocketException;

public class ThriftExceptionUtils {

    private static final String BROKEN_PIPE = "BROKEN PIPE";
    private static final String CONNECTION_RESET = "CONNECTION RESET";

    /**
   * Simple check to determine whether or not the supplied exception is a caused by a broken socket.
   * @param thriftException The thrift exception.
   * @return True if the cause of the supplied exception is a broken pipe, or connection reset socket exception.
   */
   public static boolean isBrokenSocket(TException thriftException) {
     boolean brokenSocket = false;
     Throwable cause = thriftException.getCause();
     if ( cause != null && cause instanceof SocketException) {
       String message = cause.getMessage();
       if ( message != null ) {
         message = message.toUpperCase();
         if( message.contains(BROKEN_PIPE) || message.contains(CONNECTION_RESET) ) {
           brokenSocket = true;
         }
       }
     }
     return brokenSocket;
   }
}
