class Main {
    private static final Prologue = "import static ApiScriptDSL.*\n"

    static void main(String[] args) {

        if (args.length != 2) {
            println("usage: SCRIPT GROUP")
            System.exit(1)
        }
        final var scriptFileName = args[0]
        final var groupName = args[1]

        GroovyShell shell = new GroovyShell()
        final var text = new File(args[0]).getText()
        final def script = """
import static ApiScriptDSL.*

${text}

run "${groupName}"
""".trim()
        shell.parse(script, scriptFileName).run()
    }
}
