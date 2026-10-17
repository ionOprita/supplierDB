package ro.sellfluence.emagdashboard;

import java.util.List;

/** Reports incomplete Ads downloads using a summary that fits the task error column. */
public final class AdsPartialFailureException extends RuntimeException {
    private static final int MAX_MESSAGE_LENGTH = 65_535;

    public AdsPartialFailureException(List<String> issues, Throwable cause) {
        super(summarizeIssues(issues, MAX_MESSAGE_LENGTH), cause);
    }

    static String summarizeIssues(List<String> issues, int maxLength) {
        var lines = List.copyOf(issues);
        if (maxLength < 0) {
            throw new IllegalArgumentException("The summary length must not be negative.");
        }

        long fullLength = Math.max(0, lines.size() - 1);
        for (var line : lines) {
            fullLength += line.length();
        }
        if (fullLength <= maxLength) {
            return String.join("\n", lines);
        }
        if (omissionSummary(lines.size()).length() > maxLength) {
            throw new IllegalArgumentException("The summary length cannot fit the omitted issue count.");
        }

        int retained = 0;
        long prefixLength = 0;
        while (retained < lines.size() - 1) {
            var candidateLength = prefixLength + (retained == 0 ? 0 : 1) + lines.get(retained).length();
            var suffix = omissionSummary(lines.size() - retained - 1);
            if (candidateLength + 1 + suffix.length() > maxLength) {
                break;
            }
            prefixLength = candidateLength;
            retained++;
        }
        var suffix = omissionSummary(lines.size() - retained);
        return retained == 0 ? suffix : String.join("\n", lines.subList(0, retained)) + "\n" + suffix;
    }

    private static String omissionSummary(int omitted) {
        return "plus %d more issues".formatted(omitted);
    }
}
