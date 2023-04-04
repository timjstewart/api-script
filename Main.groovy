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
        try {
        shell.parse(script, scriptFileName).run()
        } catch (MissingMethodException ex) {
            println("""Error in command '${ex.method}', Command arguments: '${ex.arguments}'.
Either the command does not exist or it is being called with incorrect arguments.""")
        }
    }
}
