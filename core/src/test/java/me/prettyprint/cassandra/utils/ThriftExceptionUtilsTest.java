package me.prettyprint.cassandra.utils;

import org.apache.thrift.TException;
import org.junit.Test;

import java.net.SocketException;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@SuppressWarnings({"ThrowableResultOfMethodCallIgnored"})
public class ThriftExceptionUtilsTest {

  @Test
  public void testBrokenPipeLower() {
    assertTrue(checkException("this is a broken pipe error"));
  }

  @Test
  public void testBrokenPipeUpper() {
    assertTrue(checkException(" some BROKEN PIPE problem"));
  }

  @Test
  public void testConnectionResetCamel() {
    assertTrue(checkException("this is a Connection reset error"));
  }

  @Test
  public void testOther() {
    assertFalse(checkException("some other error"));
  }

  private boolean checkException(String message) {
    return ThriftExceptionUtils.isBrokenSocket(new TException(new SocketException(message)));
  }
}
