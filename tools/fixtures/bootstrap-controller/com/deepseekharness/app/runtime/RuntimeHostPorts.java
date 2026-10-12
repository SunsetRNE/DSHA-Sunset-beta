package com.deepseekharness.app.runtime;

import java.util.ArrayList;
import java.util.List;

/** 主机测试替身：把诊断记录留在进程内存里，便于断言失败原文确实被留下。 */
public final class RuntimeHostPorts {
  public interface Sink {
    void record(String kind, String detail);
  }

  public static final List<String> records = new ArrayList<>();

  private RuntimeHostPorts() {}

  public static Sink fromOwner(Object owner) {
    return (kind, detail) -> records.add(kind + ":" + detail);
  }

  public static void reset() {
    records.clear();
  }
}
