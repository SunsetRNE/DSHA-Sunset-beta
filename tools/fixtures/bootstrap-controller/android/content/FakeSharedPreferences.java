package android.content;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 可注入提交失败的内存偏好。
 *
 * <p>{@link #commit()} 返回 false 时**一个值都不写** —— 与 Android SharedPreferences 的
 * 失败语义一致，控制器因此必须自己处理"请求/结果没存下来"的路径，不能假装成功。
 */
public final class FakeSharedPreferences implements SharedPreferences {
  /** 提交门；按本次提交涉及的键判定是否允许落盘。 */
  public interface CommitGate {
    boolean allow(Set<String> keys);
  }

  private final Map<String, Object> values = new LinkedHashMap<>();
  private CommitGate gate = keys -> true;

  /** 测试夹具直接播种已知状态，绕过提交门。 */
  public FakeSharedPreferences put(String key, Object value) {
    values.put(key, value);
    return this;
  }

  public FakeSharedPreferences gate(CommitGate replacement) {
    gate = replacement;
    return this;
  }

  @Override
  public boolean getBoolean(String key, boolean fallback) {
    Object value = values.get(key);
    return value instanceof Boolean ? (Boolean) value : fallback;
  }

  @Override
  public long getLong(String key, long fallback) {
    Object value = values.get(key);
    return value instanceof Long ? (Long) value : fallback;
  }

  @Override
  public String getString(String key, String fallback) {
    Object value = values.get(key);
    return value instanceof String ? (String) value : fallback;
  }

  @Override
  public boolean contains(String key) {
    return values.containsKey(key);
  }

  @Override
  public Editor edit() {
    return new Pending();
  }

  private final class Pending implements Editor {
    private final Map<String, Object> staged = new LinkedHashMap<>();

    @Override
    public Editor putBoolean(String key, boolean value) {
      staged.put(key, value);
      return this;
    }

    @Override
    public Editor putLong(String key, long value) {
      staged.put(key, value);
      return this;
    }

    @Override
    public Editor putString(String key, String value) {
      staged.put(key, value);
      return this;
    }

    @Override
    public boolean commit() {
      if (!gate.allow(staged.keySet())) return false;
      values.putAll(staged);
      return true;
    }
  }
}
