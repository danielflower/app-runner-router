package scaffolding;

import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeDiagnosingMatcher;

import java.net.http.HttpResponse;

import static org.hamcrest.CoreMatchers.is;

public class ContentResponseMatcher {
    public static Matcher<HttpResponse<String>> equalTo(int statusCode, Matcher<String> contentMatcher) {
        Matcher<Integer> statusCodeMatcher = is(statusCode);
        return new TypeSafeDiagnosingMatcher<HttpResponse<String>>() {
            protected boolean matchesSafely(HttpResponse<String> t, Description description) {
                boolean matches = true;
                if (!statusCodeMatcher.matches(t.statusCode())) {
                    description.appendText("statusCode ");
                    statusCodeMatcher.describeMismatch(t.statusCode(), description);
                    matches = false;
                }

                if (!contentMatcher.matches(t.body())) {
                    if (!matches)
                        description.appendText(", ");

                    description.appendText("content ");
                    contentMatcher.describeMismatch(t.body(), description);
                    matches = false;
                }

                return matches;
            }

            public void describeTo(Description description) {
                description
                    .appendText("{statusCode ")
                    .appendDescriptionOf(statusCodeMatcher)
                    .appendText(", content ")
                    .appendDescriptionOf(contentMatcher)
                    .appendText("}");
            }
        };
    }
}
