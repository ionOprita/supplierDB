package ro.sellfluence.support;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The Arguments class is responsible for parsing command-line arguments.
 * It supports options in the format `--option=value` and positional arguments.
 */
public class Arguments {
    /**
     * List of positional arguments.
     */
    private final List<String> remainingArguments = new ArrayList<>();

    /**
     * List of flags.
     * This includes single-letter flags and long flags.
     */
    private final List<String> flags = new ArrayList<>();

    /**
     * Stores options and their corresponding values.
     */
    private final Map<String, String> options = new HashMap<>();

    /**
     * A regular expression pattern used to validate and match words structured with alphanumeric
     * characters, dashes, and underscores.
     *
     * The word must:
     * - Start and end with an alphanumeric character (a-z, 0-9).
     * - Contain alphanumeric characters, or dashes and underscores, with dashes or underscores
     *   only being valid when followed by an alphanumeric character.
     *
     * Examples of valid words include:
     * - "word"
     * - "word-word"
     * - "word_word"
     * - "word1-word2"
     * - "word_1"
     */
    private static final String wordPattern = "[a-z0-9](?:[a-z0-9]|[-_](?=[a-z0-9]))*[a-z0-9]";

    /**
     * A regular expression pattern used to match single-letter flags.
     * More than one flag can be specified in a single string.
     */
    private static final Pattern singleLetterFlagPattern = Pattern.compile("-([a-z0-9]+)");

    /**
     * A regular expression pattern used to match long flags.
     */
    private static final Pattern longFlagPattern = Pattern.compile("--(" + wordPattern + ")");

    /**
     * A regular expression pattern used to match options with an argument.
     */
    private static final Pattern optionWithArgumentPattern = Pattern.compile("--(" + wordPattern + ")=(.*)");

    /**
     * Constructs an Arguments object to parse command-line arguments.
     * Processes a given array of strings, identifying options, flags,
     * and positional arguments.
     *
     * @param args the array of command-line arguments to process.
     */
    public Arguments(String[] args) {
        for (String arg : args) {
            var m = optionWithArgumentPattern.matcher(arg);
            if (m.matches()) {
                var option = m.group(1);
                var value = m.group(2);
                options.put(option, value);
            } else {
                m = longFlagPattern.matcher(arg);
                if (m.matches()) {
                    var option = m.group(1);
                    flags.add(option);
                } else {
                    m = singleLetterFlagPattern.matcher(arg);
                    if (m.matches()) {
                        var options = m.group(1);
                        options.chars()
                                .mapToObj(c -> String.valueOf((char) c))
                                .forEach(flags::add);
                    } else {
                        remainingArguments.add(arg);
                    }
                }
            }
        }
    }

    /**
     * Get the list of positional arguments.
     * These are all arguments that were not parsed as flags or options.
     *
     * @return list of positional arguments.
     */
    public List<String> getPositionalArguments() {
        return Collections.unmodifiableList(remainingArguments);
    }

    /**
     * Check if the given flag is present.
     * The flag can either be a single letter like `-f` or a long flag like `--flag`
     * but must not have an argument like `--flag=value`.
     *
     * @param option option name.
     * @return true if the option is present, false otherwise.
     */
    public boolean hasFlag(String option) {
        return flags.contains(option);
    }

    /**
     * Check if the given option is present.
     * This checks only for options with an argument like `--option=value`.
     *
     * @param option option name.
     * @return true if the option is present, false otherwise.
     */
    public boolean hasOption(String option) {
        return options.containsKey(option);
    }

    /**
     * Get the value of the given option.
     * An option must have a format of `--option=value`.
     * If no =value is present, it is considered to be a flag and can be checked for by {@link #hasFlag(String)}.
     *
     * @param option option name.
     * @return value of the option or null if the option is not present.
     */
    public String getOption(String option) {
        return options.get(option);
    }

    /**
     * Get the value of the given option.
     * If the option is not present, return the given default value.
     *
     * @param option option name.
     * @param defaultValue default value.
     * @return value of the option or the default value.
     */
    public String getOption(String option, String defaultValue) {
        String value = options.get(option);
        return value == null ? defaultValue : value;
    }
}