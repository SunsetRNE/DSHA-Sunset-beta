package com.deepseekharness.app.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

/** Android ADB 公钥及共享授权表的纯字节变换；从不读取私钥或默认清空授权表。 */
public final class AdbAuthorizedKeys {
  public static final int MAX_TABLE_BYTES = 1024 * 1024;

  private AdbAuthorizedKeys() {}

  public static String publicKey(String input) throws IOException {
    if (input == null || input.length() > 2048) throw new IOException("PUBLIC_KEY_INVALID");
    for (int i = 0; i < input.length(); i++) {
      char c = input.charAt(i);
      if (Character.isISOControl(c) && c != '\r' && c != '\n' && c != '\t')
        throw new IOException("PUBLIC_KEY_INVALID");
    }
    String line = input.trim();
    if (line.indexOf('\n') >= 0 || line.indexOf('\r') >= 0 || line.indexOf('\0') >= 0)
      throw new IOException("PUBLIC_KEY_MULTILINE");
    String token = line.split("[ \\t]+", 2)[0];
    byte[] bytes;
    try {
      bytes = Base64.getDecoder().decode(token);
    } catch (IllegalArgumentException bad) {
      throw new IOException("PUBLIC_KEY_INVALID", bad);
    }
    if (bytes.length != 524 || !Base64.getEncoder().encodeToString(bytes).equals(token))
      throw new IOException("PUBLIC_KEY_FORMAT_UNSUPPORTED");
    ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    int words = buffer.getInt(0), exponent = buffer.getInt(520);
    // Android 的 2048-bit RSAPublicKey：64 个 word、奇数模数、最高位和支持的指数。
    if (words != 64
        || (bytes[8] & 1) != 1
        || (bytes[263] & 128) == 0
        || (exponent != 3 && exponent != 65537))
      throw new IOException("PUBLIC_KEY_FORMAT_UNSUPPORTED");
    java.math.BigInteger modulus = littleInteger(bytes, 8, 256);
    java.math.BigInteger wordBase = java.math.BigInteger.ONE.shiftLeft(32);
    long inverse = wordBase.subtract(littleInteger(bytes, 8, 4).modInverse(wordBase)).longValue();
    if (Integer.toUnsignedLong(buffer.getInt(4)) != inverse
        || !littleInteger(bytes, 264, 256)
            .equals(java.math.BigInteger.ONE.shiftLeft(4096).mod(modulus)))
      throw new IOException("PUBLIC_KEY_MONTGOMERY_INVALID");
    return token;
  }

  private static java.math.BigInteger littleInteger(byte[] bytes, int offset, int length) {
    byte[] reversed = new byte[length];
    for (int i = 0; i < length; i++) reversed[length - i - 1] = bytes[offset + i];
    return new java.math.BigInteger(1, reversed);
  }

  public static final class Change {
    public final byte[] before, after;
    public final String ownedLine;
    public final boolean inserted;

    private Change(byte[] before, byte[] after, String ownedLine, boolean inserted) {
      this.before = before.clone();
      this.after = after.clone();
      this.ownedLine = ownedLine;
      this.inserted = inserted;
    }
  }

  public static Change authorize(byte[] table, String publicKey, String owner) throws IOException {
    checkTable(table);
    String key = publicKey(publicKey);
    if (!SelfPackageIdentity.validName(owner)) throw new IOException("KEY_OWNER_INVALID");
    if (count(table, key) > 0) return new Change(table, table, "", false);
    String owned = key + " dsha:" + owner;
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    output.write(table);
    if (table.length != 0 && table[table.length - 1] != '\n') output.write('\n');
    output.write((owned + "\n").getBytes(StandardCharsets.US_ASCII));
    byte[] after = output.toByteArray();
    checkTable(after);
    return new Change(table, after, owned, true);
  }

  /** 只删除一条逐字节匹配的所有权记录；其它授权及其顺序/换行保持原样。 */
  public static Change revoke(byte[] table, String ownedLine) throws IOException {
    checkTable(table);
    String key = publicKey(ownedLine);
    if (!ownedLine.matches("[A-Za-z0-9+/=]+ dsha:[A-Za-z][A-Za-z0-9_.]*"))
      throw new IOException("KEY_OWNERSHIP_UNVERIFIED");
    byte[] owned = ownedLine.getBytes(StandardCharsets.US_ASCII);
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    int removed = 0;
    for (int start = 0; start < table.length; ) {
      int end = start;
      while (end < table.length && table[end] != '\n') end++;
      byte[] line = Arrays.copyOfRange(table, start, end);
      int next = end < table.length ? end + 1 : end;
      if (Arrays.equals(line, owned)) removed++;
      else output.write(table, start, next - start);
      start = next;
    }
    int matches = count(table, key);
    if (removed == 0 && matches == 0) return new Change(table, table, ownedLine, false);
    if (removed != 1 || matches != 1) throw new IOException("KEY_OWNERSHIP_CONFLICT");
    return new Change(table, output.toByteArray(), ownedLine, false);
  }

  public static int count(byte[] table, String key) throws IOException {
    checkTable(table);
    int count = 0;
    byte[] token = key.getBytes(StandardCharsets.US_ASCII);
    for (int start = 0; start < table.length; ) {
      int end = start;
      while (end < table.length && table[end] != '\n') end++;
      int tokenStart = start;
      while (tokenStart < end
          && (table[tokenStart] == ' ' || table[tokenStart] == '\t' || table[tokenStart] == '\r'))
        tokenStart++;
      int tokenEnd = tokenStart;
      while (tokenEnd < end
          && table[tokenEnd] != ' '
          && table[tokenEnd] != '\t'
          && table[tokenEnd] != '\r') tokenEnd++;
      if (Arrays.equals(Arrays.copyOfRange(table, tokenStart, tokenEnd), token)) count++;
      start = end < table.length ? end + 1 : end;
    }
    return count;
  }

  private static void checkTable(byte[] table) throws IOException {
    if (table == null || table.length > MAX_TABLE_BYTES)
      throw new IOException("KEY_TABLE_TOO_LARGE");
    for (byte value : table) if (value == 0) throw new IOException("KEY_TABLE_INVALID");
  }
}
