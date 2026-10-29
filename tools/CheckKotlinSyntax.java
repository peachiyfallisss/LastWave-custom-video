import java.nio.file.Files;
import java.nio.file.Path;
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles;
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment;
import org.jetbrains.kotlin.config.CompilerConfiguration;
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer;
import org.jetbrains.kotlin.com.intellij.psi.PsiErrorElement;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.kotlin.psi.KtPsiFactory;

/** Syntax only: explicitly not a replacement for Android/Kotlin type checks. */
public final class CheckKotlinSyntax {
    public static void main(String[] args) throws Exception {
        var disposable = Disposer.newDisposable();
        try {
            var environment = KotlinCoreEnvironment.createForProduction(
                disposable, new CompilerConfiguration(), EnvironmentConfigFiles.JVM_CONFIG_FILES);
            var factory = new KtPsiFactory(environment.getProject(), false);
            int errors = 0;
            for (String name : args) {
                var path = Path.of(name);
                var source = Files.readString(path).replace("\r\n", "\n").replace("\r", "\n");
                var parsed = factory.createFile(path.getFileName().toString(), source);
                for (var error : PsiTreeUtil.collectElementsOfType(parsed, PsiErrorElement.class)) {
                    int line = 1 + (int) source.substring(0, error.getTextOffset()).chars().filter(c -> c == '\n').count();
                    System.err.println(name + ":" + line + ": " + error.getErrorDescription());
                    errors++;
                    if (errors >= 40) throw new IllegalStateException("Stopped after 40 syntax errors");
                }
            }
            if (errors != 0) throw new IllegalStateException(errors + " Kotlin syntax errors");
            System.out.println("Kotlin syntax: no error spans in " + args.length + " files (not type checking).");
        } finally { Disposer.dispose(disposable); }
    }
}
