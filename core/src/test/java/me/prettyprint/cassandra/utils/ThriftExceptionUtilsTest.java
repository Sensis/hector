package me.prettyprint.cassandra.utils;

import org.apache.thrift.TException;
import org.junit.Test;

import java.net.SocketException;

import static org.junit.Assert.assertTrue;

@SuppressWarnings({"ThrowableResultOfMethodCallIgnored"})
public class ThriftExceptionUtilsTest {

  @Test
  public void testBrokenPipeLower() {
    assertTrue(ThriftExceptionUtils.isBrokenSocket(createException("this is a broken pipe error")));
  }

  @Test
  public void testBrokenPipeUpper() {
    assertTrue(ThriftExceptionUtils.isBrokenSocket(createException(" some BROKEN PIPE problem")));
  }

  @Test
  public void testConnectionResetCamel() {
    assertTrue(ThriftExceptionUtils.isBrokenSocket(createException("this is a Connection reset error")));
  }

  private TException createException(String message) {
    return new TException(new SocketException(message));
  }
}
