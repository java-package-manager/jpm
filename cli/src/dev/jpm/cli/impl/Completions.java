package dev.jpm.cli.impl;

import java.util.ArrayList;
import java.util.List;

final class Completions {
    private Completions() {
    }

    static final List<String> SHELLS = List.of("bash", "zsh", "fish", "powershell");

    private static final List<String> GLOBAL = List.of("--verbose", "--quiet", "--color", "--offline", "--help", "--version");

    static String script(String shell, List<Command> commands) {
        return switch (shell) {
            case "bash" -> bash(commands);
            case "zsh" -> zsh(commands);
            case "fish" -> fish(commands);
            case "powershell" -> powershell(commands);
            default -> throw new IllegalArgumentException(shell);
        };
    }

    private static List<String> names(Command c) {
        List<String> out = new ArrayList<>(List.of(c.name()));
        out.addAll(c.aliases());
        return out;
    }

    private static String allNames(List<Command> commands) {
        List<String> out = new ArrayList<>();
        for (Command c : commands) out.addAll(names(c));
        return String.join(" ", out);
    }

    private static String bash(List<Command> commands) {
        StringBuilder s = new StringBuilder("# jpm completions for bash: `source <(jpm completions bash)`\n_jpm() {\n");
        s.append("    local cur=\"${COMP_WORDS[COMP_CWORD]}\"\n");
        s.append("    if [ \"$COMP_CWORD\" -eq 1 ]; then\n");
        s.append("        COMPREPLY=($(compgen -W \"").append(allNames(commands)).append(' ').append(String.join(" ", GLOBAL)).append("\" -- \"$cur\"))\n");
        s.append("        return\n    fi\n    case \"${COMP_WORDS[1]}\" in\n");
        for (Command c : commands) {
            if (c.flags().isEmpty()) continue;
            s.append("        ").append(String.join("|", names(c))).append(") COMPREPLY=($(compgen -W \"")
                    .append(String.join(" ", c.flags())).append("\" -- \"$cur\")) ;;\n");
        }
        s.append("    esac\n}\ncomplete -o default -F _jpm jpm\n");
        return s.toString();
    }

    private static String zsh(List<Command> commands) {
        StringBuilder s = new StringBuilder("#compdef jpm\n# jpm completions for zsh: `source <(jpm completions zsh)`, or save as _jpm in your $fpath\n");
        s.append("_jpm() {\n    if (( CURRENT == 2 )); then\n        local -a commands\n        commands=(\n");
        for (Command c : commands) {
            String summary = c.usages().isEmpty() ? "" : c.usages().get(0).summary();
            for (String n : names(c)) s.append("            ").append(quote(n + ":" + summary.replace(":", "\\:"))).append('\n');
        }
        s.append("        )\n        _describe 'command' commands\n        return\n    fi\n    case $words[2] in\n");
        for (Command c : commands) {
            if (c.flags().isEmpty()) continue;
            s.append("        ").append(String.join("|", names(c))).append(") compadd -- ").append(String.join(" ", c.flags())).append(" ;;\n");
        }
        s.append("    esac\n    _files\n}\n");
        s.append("if [ \"$funcstack[1]\" = _jpm ]; then\n    _jpm \"$@\"\nelse\n    compdef _jpm jpm\nfi\n");
        return s.toString();
    }

    private static String fish(List<Command> commands) {
        StringBuilder s = new StringBuilder("# jpm completions for fish: `jpm completions fish > ~/.config/fish/completions/jpm.fish`\n");
        s.append("complete -c jpm -f\n");
        for (Command c : commands) {
            String summary = c.usages().isEmpty() ? "" : c.usages().get(0).summary();
            for (String n : names(c)) {
                s.append("complete -c jpm -n __fish_use_subcommand -a ").append(n).append(" -d ").append(fishQuote(summary)).append('\n');
            }
            for (String f : c.flags()) {
                s.append("complete -c jpm -n '__fish_seen_subcommand_from ").append(String.join(" ", names(c))).append("' ");
                if (f.startsWith("--")) s.append("-l ").append(f.substring(2));
                else if (f.startsWith("-")) s.append("-s ").append(f.substring(1));
                else s.append("-a ").append(f);
                s.append('\n');
            }
        }
        s.append("complete -c jpm -n '__fish_seen_subcommand_from run start dev build test info import patch' -F\n");
        return s.toString();
    }

    private static String powershell(List<Command> commands) {
        StringBuilder s = new StringBuilder("# jpm completions for PowerShell: `jpm completions powershell | Out-String | Invoke-Expression`\n");
        s.append("Register-ArgumentCompleter -Native -CommandName jpm -ScriptBlock {\n");
        s.append("    param($wordToComplete, $commandAst, $cursorPosition)\n");
        s.append("    $words = @($commandAst.CommandElements | ForEach-Object { $_.ToString() })\n");
        s.append("    $flags = @{\n");
        for (Command c : commands) {
            if (c.flags().isEmpty()) continue;
            for (String n : names(c)) {
                s.append("        '").append(n).append("' = @(");
                List<String> q = new ArrayList<>();
                for (String f : c.flags()) q.add("'" + f + "'");
                s.append(String.join(", ", q)).append(")\n");
            }
        }
        s.append("    }\n");
        s.append("    if ($words.Count -le 2 -and -not ($words.Count -eq 2 -and $wordToComplete -eq '')) {\n");
        s.append("        $candidates = @(").append(quotedList(allNames(commands) + " " + String.join(" ", GLOBAL))).append(")\n");
        s.append("    } else {\n        $candidates = $flags[$words[1]]\n    }\n");
        s.append("    $candidates | Where-Object { $_ -like \"$wordToComplete*\" } | ForEach-Object {\n");
        s.append("        [System.Management.Automation.CompletionResult]::new($_, $_, 'ParameterValue', $_)\n    }\n}\n");
        return s.toString();
    }

    private static String quotedList(String words) {
        List<String> out = new ArrayList<>();
        for (String w : words.split(" ")) out.add("'" + w + "'");
        return String.join(", ", out);
    }

    private static String fishQuote(String s) {
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    private static String quote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
