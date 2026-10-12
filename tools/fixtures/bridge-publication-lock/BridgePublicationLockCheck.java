import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.SynchronizedTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreeScanner;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 静态结构检查：用 JDK 编译器 {@code JavacTask.parse}（只 parse、不 analyze，因此 Android /
 * 三方类型不需要在 classpath 上）证明 {@code HttpShellService.bindAndServe} 里
 * 「发布就绪端点 publishReadyEndpointFor」「LIFECYCLE.publish」以及就绪赋值
 * 「running = true」「instance = this」落在同一把 {@code synchronized (LIFECYCLE)} 临界区内，
 * 并且该临界区里 publishReadyEndpointFor 先于 LIFECYCLE.publish、再先于 running = true。
 *
 * <p>为什么是 parse 而不是 analyze：分析会要求解析 Android 类型；结构不变量只依赖语法树形状。
 *
 * <p>刻意声明边界：这是编译期结构回归，<b>不是</b> Android 运行期竞态证据 —— 它只排除
 * 「恢复流程能在两步之间写回 0」这一类结构错误，不代替真机/动态验证。
 *
 * <p>参数：一个 Java 源文件路径。退出码 0 = 通过；1 = 结构不变量被破坏；2 = 编译器/用法失败；
 * 3 = 源码解析失败。
 */
public final class BridgePublicationLockCheck {

  /** 锁对象标识符；只有 synchronized (LIFECYCLE) 才算临界区。 */
  private static final String LOCK = "LIFECYCLE";

  /** 必须在同一临界区内的四个结构标记（顺序即要求的先后顺序）。 */
  private static final List<String> MARKERS =
      List.of("publishReadyEndpointFor", "LIFECYCLE.publish", "running", "instance");

  private BridgePublicationLockCheck() {}

  public static void main(String[] args) throws Exception {
    if (args.length != 1) {
      systemExit("USAGE_EXPECTS_ONE_SOURCE_PATH");
    }
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    if (compiler == null) {
      System.err.println("FAIL_NO_SYSTEM_JAVA_COMPILER");
      System.exit(2);
    }
    DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
    StandardJavaFileManager fileManager =
        compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8);
    JavacTask task =
        (JavacTask)
            compiler.getTask(
                null,
                fileManager,
                diagnostics,
                Arrays.asList("-proc:none", "-nowarn"),
                null,
                fileManager.getJavaFileObjects(new File(args[0])));
    List<CompilationUnitTree> units = new ArrayList<>();
    for (CompilationUnitTree unit : task.parse()) {
      units.add(unit);
    }
    for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
      if (diagnostic.getKind() == Diagnostic.Kind.ERROR) {
        System.err.println(
            "  ERROR line " + diagnostic.getLineNumber() + ": " + diagnostic.getMessage(null));
        systemExit("PARSE_FAILED:" + args[0]);
      }
    }
    List<MethodTree> methods = new ArrayList<>();
    for (CompilationUnitTree unit : units) {
      new TreeScanner<Void, Void>() {
        @Override
        public Void visitMethod(MethodTree node, Void ignored) {
          if (node.getName().contentEquals("bindAndServe")) {
            methods.add(node);
          }
          return super.visitMethod(node, ignored);
        }
      }.scan(unit, null);
    }
    if (methods.isEmpty()) {
      systemExit("MISSING_BIND_AND_SERVE:" + args[0]);
    }
    for (MethodTree method : methods) {
      checkMethod(method);
    }
    System.out.println(
        "PASS bindAndServe synchronized("
            + LOCK
            + ") publication lock in "
            + args[0]
            + " (methods="
            + methods.size()
            + ", AST parse-only)");
    System.exit(0);
  }

  /** 按 MARKERS 的先后顺序证明每个标记都只在同一把 LIFECYCLE 锁内出现一次。 */
  private static void checkMethod(MethodTree method) {
    Tree body = method.getBody();
    if (body == null) {
      systemExit("BIND_AND_SERVE_WITHOUT_BODY");
    }
    List<String> wholeMethod = hits(body);
    for (String marker : MARKERS) {
      if (count(wholeMethod, marker) == 0) {
        systemExit("MARKER_MISSING:" + marker);
      }
    }
    SynchronizedTree lock = null;
    for (SynchronizedTree candidate : lifecycleBlocks(body)) {
      if (count(hits(candidate.getBlock()), "LIFECYCLE.publish") > 0) {
        lock = candidate;
        break;
      }
    }
    if (lock == null) {
      systemExit("NO_SYNCHRONIZED_LIFECYCLE_BLOCK_WITH_PUBLISH");
    }
    List<String> inside = hits(lock.getBlock());
    for (String marker : MARKERS) {
      int locked = count(inside, marker);
      if (locked == 0 || count(wholeMethod, marker) != locked) {
        systemExit("OUTSIDE_LIFECYCLE_BLOCK:" + marker);
      }
      if (locked > 1) {
        systemExit("DUPLICATE_MARKER_IN_LIFECYCLE_BLOCK:" + marker);
      }
    }
    int endpoint = inside.indexOf("publishReadyEndpointFor");
    int publish = inside.indexOf("LIFECYCLE.publish");
    int running = inside.indexOf("running");
    if (!(endpoint < publish && publish < running)) {
      systemExit(
          "ORDER_VIOLATION:expected publishReadyEndpointFor<LIFECYCLE.publish<running, got index "
              + endpoint
              + "/"
              + publish
              + "/"
              + running);
    }
  }

  /** 方法体内所有 synchronized (LIFECYCLE) 块。 */
  private static List<SynchronizedTree> lifecycleBlocks(Tree body) {
    List<SynchronizedTree> blocks = new ArrayList<>();
    new TreeScanner<Void, Void>() {
      @Override
      public Void visitSynchronized(SynchronizedTree node, Void ignored) {
        Tree lock = unwrap(node.getExpression());
        if (lock instanceof IdentifierTree
            && ((IdentifierTree) lock).getName().contentEquals(LOCK)) {
          blocks.add(node);
        }
        return super.visitSynchronized(node, ignored);
      }
    }.scan(body, null);
    return blocks;
  }

  /** javac 在 synchronized (X) 的表达式上保留括号节点（JCParens），比较前先剥掉。 */
  private static Tree unwrap(Tree expression) {
    Tree current = expression;
    while (current instanceof ParenthesizedTree) {
      current = ((ParenthesizedTree) current).getExpression();
    }
    return current;
  }

  /** 按先序遍历顺序收集结构标记；块内语句顺序即源顺序。 */
  private static List<String> hits(Tree tree) {
    List<String> found = new ArrayList<>();
    new TreeScanner<Void, List<String>>() {
      @Override
      public Void visitMethodInvocation(MethodInvocationTree node, List<String> sink) {
        if (isLocalCall(node, "publishReadyEndpointFor")) {
          sink.add("publishReadyEndpointFor");
        }
        if (isLifecyclePublish(node)) {
          sink.add("LIFECYCLE.publish");
        }
        return super.visitMethodInvocation(node, sink);
      }

      @Override
      public Void visitAssignment(AssignmentTree node, List<String> sink) {
        if (isTrueAssignment(node, "running")) {
          sink.add("running");
        }
        if (isThisAssignment(node, "instance")) {
          sink.add("instance");
        }
        return super.visitAssignment(node, sink);
      }
    }.scan(tree, found);
    return found;
  }

  private static boolean isLocalCall(MethodInvocationTree node, String name) {
    Tree select = node.getMethodSelect();
    if (select instanceof IdentifierTree) {
      return ((IdentifierTree) select).getName().contentEquals(name);
    }
    return select instanceof MemberSelectTree
        && ((MemberSelectTree) select).getIdentifier().contentEquals(name)
        && ((MemberSelectTree) select).getExpression() instanceof IdentifierTree
        && ((IdentifierTree) ((MemberSelectTree) select).getExpression())
            .getName()
            .contentEquals("this");
  }

  private static boolean isLifecyclePublish(MethodInvocationTree node) {
    if (!(node.getMethodSelect() instanceof MemberSelectTree)) {
      return false;
    }
    MemberSelectTree select = (MemberSelectTree) node.getMethodSelect();
    return select.getIdentifier().contentEquals("publish")
        && select.getExpression() instanceof IdentifierTree
        && ((IdentifierTree) select.getExpression()).getName().contentEquals(LOCK);
  }

  private static boolean isTrueAssignment(AssignmentTree node, String name) {
    return node.getVariable() instanceof IdentifierTree
        && ((IdentifierTree) node.getVariable()).getName().contentEquals(name)
        && node.getExpression() instanceof LiteralTree
        && Boolean.TRUE.equals(((LiteralTree) node.getExpression()).getValue());
  }

  private static boolean isThisAssignment(AssignmentTree node, String name) {
    return node.getVariable() instanceof IdentifierTree
        && ((IdentifierTree) node.getVariable()).getName().contentEquals(name)
        && node.getExpression() instanceof IdentifierTree
        && ((IdentifierTree) node.getExpression()).getName().contentEquals("this");
  }

  private static int count(List<String> values, String marker) {
    int total = 0;
    for (String value : values) {
      if (value.equals(marker)) {
        total++;
      }
    }
    return total;
  }

  private static void systemExit(String failure) {
    System.out.println("FAIL " + failure);
    System.exit(1);
  }
}
