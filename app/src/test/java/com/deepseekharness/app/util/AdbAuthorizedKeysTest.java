package com.deepseekharness.app.util;

import static org.junit.Assert.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.Test;

public final class AdbAuthorizedKeysTest {
  public static String key(int seed) {
    byte[] data = new byte[524];
    ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
    b.putInt(0, 64);
    b.putInt(520, 65537);
    data[8] = (byte) (seed | 1);
    data[263] = (byte) 128;
    java.math.BigInteger modulus =
        java.math.BigInteger.ONE.shiftLeft(2047).add(java.math.BigInteger.valueOf(seed | 1));
    java.math.BigInteger base = java.math.BigInteger.ONE.shiftLeft(32);
    b.putInt(4, base.subtract(java.math.BigInteger.valueOf(seed | 1).modInverse(base)).intValue());
    byte[] rr = java.math.BigInteger.ONE.shiftLeft(4096).mod(modulus).toByteArray();
    for (int i = 0; i < Math.min(rr.length, 256); i++) data[264 + i] = rr[rr.length - i - 1];
    return Base64.getEncoder().encodeToString(data);
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }

  @Test
  public void appendsAndSelectivelyRevokesWithoutChangingOtherKeys() throws Exception {
    byte[] original = bytes(key(3) + " somebody\r\n# 注释保持字节\n");
    AdbAuthorizedKeys.Change added =
        AdbAuthorizedKeys.authorize(original, key(5) + " untrusted-comment", "com.dsh.client");
    assertTrue(added.inserted);
    assertEquals(key(5) + " dsha:com.dsh.client", added.ownedLine);
    assertArrayEquals(original, AdbAuthorizedKeys.revoke(added.after, added.ownedLine).after);
  }

  @Test
  public void preexistingKeyIsNeverClaimedAsOwned() throws Exception {
    byte[] original = bytes(key(3) + " another-owner\n");
    var result =
        AdbAuthorizedKeys.authorize(original, key(3) + " different-comment", "com.dsh.client");
    assertFalse(result.inserted);
    assertEquals("", result.ownedLine);
    assertArrayEquals(original, result.after);
  }

  @Test
  public void preservesConcurrentUnrelatedEntries() throws Exception {
    var added = AdbAuthorizedKeys.authorize(bytes(key(3) + " first\n"), key(5), "com.dsh.client");
    byte[] later = bytes(new String(added.after, StandardCharsets.UTF_8) + key(7) + " later\n");
    assertArrayEquals(
        bytes(key(3) + " first\n" + key(7) + " later\n"),
        AdbAuthorizedKeys.revoke(later, added.ownedLine).after);
  }

  @Test
  public void addsSeparatorBeforeAppendingToMissingNewline() throws Exception {
    byte[] original = bytes(key(3) + " owner");
    var result = AdbAuthorizedKeys.authorize(original, key(5), "com.dsh.client");
    assertEquals(1, AdbAuthorizedKeys.count(result.after, key(3)));
    assertEquals(1, AdbAuthorizedKeys.count(result.after, key(5)));
  }

  @Test
  public void refusesAmbiguousOrChangedOwnership() throws Exception {
    String line = key(3) + " dsha:com.dsh.client";
    for (byte[] table :
        new byte[][] {
          bytes(line + "\n" + line + "\n"),
          bytes(line + " changed\n"),
          bytes(line + "\n" + key(3) + " other\n")
        }) {
      try {
        AdbAuthorizedKeys.revoke(table, line);
        fail("must refuse ownership ambiguity");
      } catch (IOException expected) {
      }
    }
  }

  @Test
  public void rejectsMalformedPublicKeysAndOversizedTables() throws Exception {
    for (String invalid :
        new String[] {
          "",
          "ssh-rsa " + key(3),
          key(3) + "\n" + key(5),
          "AAAA",
          key(3).substring(1),
          key(3) + "\0"
        }) {
      try {
        AdbAuthorizedKeys.publicKey(invalid);
        fail("must reject malformed key");
      } catch (IOException expected) {
      }
    }
    try {
      AdbAuthorizedKeys.authorize(new byte[1048577], key(3), "com.dsh.client");
      fail();
    } catch (IOException expected) {
    }
  }

  @Test
  public void externallyRemovedOwnedKeyIsAnIdempotentNoOp() throws Exception {
    byte[] other = bytes(key(7) + " other-owner\n");
    var change = AdbAuthorizedKeys.revoke(other, key(3) + " dsha:com.dsh.client");
    assertArrayEquals(other, change.before);
    assertArrayEquals(other, change.after);
  }
}
