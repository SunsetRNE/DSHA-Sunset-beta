// 负 fixture：端点发布与 LIFECYCLE.publish 都在锁内，但就绪赋值 running = true 被挪到锁外，
// 就绪状态仍可能在临界区外被改写。检查器必须拒绝这个文件。
package fixture.negative.readiness;

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
      if (!publishReadyEndpointFor(run, run.port, localToken)) {
        closeSocket(run);
        return;
      }
      if (activeRun != run || !LIFECYCLE.publish(run.generation)) {
        closeSocket(run);
        return;
      }
      instance = this;
    }
    // 负结构：就绪赋值在锁外。
    running = true;
    noteBindOk(run.port);
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
