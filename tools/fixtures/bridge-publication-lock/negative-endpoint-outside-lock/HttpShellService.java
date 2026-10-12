// 负 fixture（旧结构）：就绪端点发布被挪出 synchronized (LIFECYCLE)，
// 恢复流程可在 publishReadyEndpointFor 与 LIFECYCLE.publish 之间写回 0。
// 检查器必须拒绝这个文件；接受就说明结构证明是假的。
package fixture.negative.endpoint;

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
    // 旧结构：就绪端点发布在锁外。
    if (!publishReadyEndpointFor(run, run.port, localToken)) {
      closeSocket(run);
      return;
    }
    synchronized (LIFECYCLE) {
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
