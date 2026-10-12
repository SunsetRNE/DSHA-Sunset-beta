package com.deepseekharness.app.util;

import org.junit.Assert;
import org.junit.Test;

/** ADB 授权表解析：指纹稳定、本应用公钥可辨认、坏行不猜。 */
public class AdbKeyTableTest {
  private static final String SAMPLE =
      "QAAAAF3Mm3sLmGNTHcZciFL61msclTnC9NdToBH/auNg3LCyC8GK8kassRNERsVmWHjNK8gVChlt5jMJ5l8BWIl/Jh/EUWphtliHqXpcQKOizg3w7UNNAI1am4YZxcyrkR+gTn1ACgnyYRJdBLin4CMeelQ2oNAm6HpVzakwzaI+lg2rn7cAh8WVeZahJtdYGhv4tPK2gQn/VMug2JFpAa9Wgt8xSpWAbkmpAtLLgnrhBssnGzaSELagkzdihjjp0SDQcTPygHFPj2QEQvYgiXviEnOEP75JRijZQKpPJwPeGrW4EK8OJYKcMxdgljyGIk2T3vzE/6402g4Vg78Yc2UbBavWtO/KfGPak5J2yv1vcDPc7c2705k+J6hbDdJjcdpoDwGCfZ+tbD5rpNxsxnVYhmXVbuF3QPtOrX5JPTk1g+SPL9dd69Wuh02XmT2C+tdpVqVoJuHpXU/zphpuFu047m2Pa2aaiWuta60KYX+brZEF/fhoS7vsKyZQdtb0rImkqCsXTYyAGk5WuRgO3uu1vSAARwyQwhH18EvKPQJdof0HQwQOjAj12Me6YbS/aVoNqnsOci5KpnvMTAtK6MuTa6jFscysfCeU6IAg3uvoB+uCFUDK7qGerWi4ikeNbXzA0gzX74Piw3JwWTiDhK2h1TSr7Cluf9FTiSaveaM6UgYpSe1YMgEAAQA= unknown@localhost\n"
          + "QAAAABFgC5cPb85Kdf0PrD6ZROsgMTdrqplp8Ip8mirXgj24PIvUHK7dzWlDZ4t09mASD7v5oaX1Hipybj6g0Ensb4Ur3XBgI1o3k2CxymRHfC141RoxCG+pt7HWvoeub8goXACzaJC3a8hXtsbUl3trCk8cv4UPnSGgSa1ZohyQceKkZiZ7T2m66yeAps9oWJ0Jy5p38weAKxK8+RcKMHU7jH5V3YKZzp6eBq7aQ1DZtIXpEIxEDJc9p219nxTROWcYBVBrUR3Jn5+KEs6SIX3ljPyy120OOUy5MDHM/tsBRSzAbp9Z5+CWTPDjpBwOtolJCGg0WD0g7f0tOjWc7n98d6dmSpW79N3EVlYXo/DPMUGdoKGfMlA/f2R2F1no9PzHmlHrZP5oz1ZC5W6moPQsRABoQQT+6X5FyJOhyRQmg53fG2f9wKFoItYCNqDHMZTMekSXSk0pIerTUPFcMfFK32+5fjtLKClMUKW/L2SVx5Andf8qRJOg2Ps/Edz//qXu0PtB942AnyxI4p5U3kX4aOWuEJqtB+Ye7KbPxswHLbjdKqlri/6pq41vTdKWtsOH3bMcZEKvqDbvmJDW1WOA6nCh8351HglM1NTccdoN3bXDZv7JbzpkLZjBLsZj0upiFPNxtHyJNVHCY42tCtVFRFXb7b9ZrKRPOa9Rz1JHMWonPFw4sQEAAQA= dsha:com.dsh.clienu.SunsetRNE\n";

  @Test
  public void parsesEntriesAndFindsTheAppKey() {
    java.util.List<AdbKeyTable.Entry> entries =
        AdbKeyTable.parse(SAMPLE, "com.dsh.clienu.SunsetRNE");
    Assert.assertEquals(2, entries.size());
    Assert.assertFalse(entries.get(0).appOwned);
    Assert.assertEquals("unknown@localhost", entries.get(0).comment);
    Assert.assertTrue(entries.get(1).appOwned);
    Assert.assertTrue(AdbKeyTable.containsAppKey(entries));
  }

  @Test
  public void fingerprintIsStableAndShapedForDisplay() {
    java.util.List<AdbKeyTable.Entry> entries = AdbKeyTable.parse(SAMPLE, "");
    Assert.assertEquals(
        entries.get(0).fingerprint, AdbKeyTable.parse(SAMPLE, "").get(0).fingerprint);
    Assert.assertTrue(entries.get(0).fingerprint.matches("([0-9A-F]{2}:){7}[0-9A-F]{2}"));
    Assert.assertNotEquals(entries.get(0).fingerprint, entries.get(1).fingerprint);
  }

  @Test
  public void badLinesDoNotGuess() {
    Assert.assertTrue(AdbKeyTable.parse("", "x").isEmpty());
    Assert.assertTrue(AdbKeyTable.parse("not-base64!!! comment", "x").get(0).fingerprint.isEmpty());
    // 没有备注的公钥不算本应用所有。
    Assert.assertFalse(AdbKeyTable.parse("QUJD", "com.dsh.client").get(0).appOwned);
  }

  @Test
  public void protocolMarkersAreNotKeyRows() {
    // 设备 shell 会在输出末尾附 [EXIT=n]；它曾被当成第三把"无备注"钥匙显示出来。
    java.util.List<AdbKeyTable.Entry> entries =
        AdbKeyTable.parse(SAMPLE + "[EXIT=0]\n", "com.dsh.clienu.SunsetRNE");
    Assert.assertEquals(2, entries.size());
    Assert.assertTrue(AdbKeyTable.containsAppKey(entries));
  }
}
