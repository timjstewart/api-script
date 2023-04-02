
class Main {
    private static final Prologue = """
import static ApiScriptDSL.*

"""
    static void main(String[] args) {
        args.each {
            GroovyShell shell = new GroovyShell()
            def script = Prologue + new File(it).getText()
            shell.parse(script, it).run()
        }
    }
}
