// 正 fixture：正确结构 —— 端点就绪发布、LIFECYCLE.publish 与 running/instance 就绪赋值
// 落在同一把 synchronized (LIFECYCLE) 里。只用于结构检查，不参与编译期类型分析。
package fixture.positive;

final class HttpShellService {

  static final Object LIFECYCLE = new Object();

  private String token;

  private Object activeRun;

  private boolean running;

  private Object instance;

  private void bindAndServe(BridgeRun run) {
    String localToken;
    synchronized (LIFECYCLE) {
      if (activeRun != run) return;
      localToken = ensureToken();
    }
    if (!publishEndpointFor(run, 0)) return;
    run.port = bindLoopbackFirstAvailable();
    synchronized (LIFECYCLE) {
      // 正确：发布就绪端点与就绪转换共用一个临界区，恢复不能在两步之间写回 0。
      if (!publishReadyEndpointFor(run, run.port, localToken)) {
        closeSocket(run);
        return;
      }
      if (activeRun != run || !LIFECYCLE.publish(run.generation)) {
        closeSocket(run);
        return;
      }
      running = true;
      instance = this;
      noteBindOk(run.port);
    }
    acceptLoop(run);
  }

  private String ensureToken() {
    return token;
  }

  private boolean publishEndpointFor(BridgeRun run, int port) {
    return true;
  }

  private boolean publishReadyEndpointFor(BridgeRun run, int port, String token) {
    return true;
  }

  private int bindLoopbackFirstAvailable() {
    return 3090;
  }

  private void closeSocket(BridgeRun run) {}

  private void noteBindOk(int port) {}

  private void acceptLoop(BridgeRun run) {}

  static final class BridgeRun {
    int port;

    long generation;
  }
}
