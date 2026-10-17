package ro.sellfluence.emagdashboard;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdsPartialFailureExceptionTest {
    @Test
    void retainsEveryIssueWhenTheyFitExactly() {
        var issues = List.of("first issue", "second issue");
        var expected = String.join("\n", issues);

        assertEquals(expected, AdsPartialFailureException.summarizeIssues(issues, expected.length()));
    }

    @Test
    void reservesSpaceForAnExactOmittedCountAndNeverCutsIssueLines() {
        var issues = List.of("issue001", "issue002", "issue003", "issue004");

        var summary = AdsPartialFailureException.summarizeIssues(issues, 28);

        assertEquals("issue001\nplus 3 more issues", summary);
        assertTrue(summary.length() <= 28);
    }

    @Test
    void retainsTheLongestPrefixWhenPrefixAndSuffixFitExactly() {
        var issues = List.of("first", "second", "third issue is too long");
        var expected = "first\nsecond\nplus 1 more issues";

        assertEquals(expected, AdsPartialFailureException.summarizeIssues(issues, expected.length()));
    }

    @Test
    void countsAllOmittedIssuesWhenOnlySixtyOneOfFiveHundredThirteenFit() {
        var issues = IntStream.range(0, 513)
                .mapToObj(index -> "Issue %03d".formatted(index))
                .toList();
        var expected = String.join("\n", issues.subList(0, 61)) + "\nplus 452 more issues";

        var summary = AdsPartialFailureException.summarizeIssues(issues, expected.length());

        assertEquals(expected, summary);
        assertEquals(62, summary.lines().count());
    }

    @Test
    void boundsTheActualExceptionMessageAndPreservesItsCause() {
        var issues = IntStream.range(0, 1_000)
                .mapToObj(index -> "Issue %04d: %s".formatted(index, "details".repeat(20)))
                .toList();
        var cause = new IllegalStateException("original failure");

        var failure = new AdsPartialFailureException(issues, cause);
        var lines = failure.getMessage().lines().toList();
        int retained = lines.size() - 1;

        assertSame(cause, failure.getCause());
        assertTrue(failure.getMessage().length() <= 65_535);
        assertEquals(issues.subList(0, retained), lines.subList(0, retained));
        assertEquals("plus %d more issues".formatted(issues.size() - retained), lines.getLast());
        var nextPrefixLength = failure.getMessage().length() - lines.getLast().length()
                + issues.get(retained).length() + 1
                + "plus %d more issues".formatted(issues.size() - retained - 1).length();
        assertTrue(nextPrefixLength > 65_535, "No further complete issue fits with its omission suffix");
    }

    @Test
    void allowsAnActualMessageThatFitsTheColumnExactly() {
        var issue = "x".repeat(65_535);

        assertEquals(issue, new AdsPartialFailureException(List.of(issue), null).getMessage());
    }

    @Test
    void replacesAnOversizedFirstIssueWithTheOmittedCount() {
        var failure = new AdsPartialFailureException(List.of("x".repeat(65_536), "second issue"), null);

        assertEquals("plus 2 more issues", failure.getMessage());
    }

    @Test
    void usesAnEmptyMessageForNoIssues() {
        assertEquals("", new AdsPartialFailureException(List.of(), null).getMessage());
        assertEquals("", AdsPartialFailureException.summarizeIssues(List.of(), 0));
    }

    @Test
    void laterChangesToTheInputListDoNotChangeTheMessage() {
        var issues = new ArrayList<>(List.of("first issue", "second issue"));
        var failure = new AdsPartialFailureException(issues, null);

        issues.clear();
        issues.add("replacement");

        assertEquals("first issue\nsecond issue", failure.getMessage());
    }
}
