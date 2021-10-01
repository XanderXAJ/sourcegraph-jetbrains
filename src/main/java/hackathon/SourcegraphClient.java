package hackathon;

import com.apollographql.apollo.ApolloCall;
import com.apollographql.apollo.ApolloClient;
import com.apollographql.apollo.api.Response;
import com.apollographql.apollo.exception.ApolloException;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.jetbrains.annotations.NotNull;
import type.SearchPatternType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

public class SourcegraphClient {
    private final ApolloClient apolloClient;

    public SourcegraphClient(SourcegraphLocation location) {
        System.out.println(location.getUri());

        if (location.requiresAuth()) {
            OkHttpClient httpClient = new OkHttpClient().newBuilder()
                    .addInterceptor(chain -> {
                        Request original = chain.request();
                        Request.Builder builder = original.newBuilder().method(original.method(), original.body());
                        builder.header("Authorization", "token " + location.getAuthToken());
                        return chain.proceed(builder.build());
                    })
                    .build();
            apolloClient = ApolloClient.builder()
                    .serverUrl(location.getUri() + "/.api/graphql")
                    .okHttpClient(httpClient)
                    .build();
        } else {
            apolloClient = ApolloClient.builder()
                    .serverUrl(location.getUri() + "/.api/graphql")
                    .build();
        }
    }

    public void literalSearchAsync(String query, boolean caseSensitive, Consumer<Search> onSuccess, Consumer<ApolloException> onError) {
        if (caseSensitive && !query.contains("case:yes")) {
            query += " case:yes";
        }
        searchAsync(query, SearchPatternType.LITERAL, onSuccess, onError);
    }
    public void regexSearchAsync(String query, Consumer<Search> onSuccess, Consumer<ApolloException> onError) {
        searchAsync(query, SearchPatternType.REGEXP, onSuccess, onError);
    }
    public void structuralSearchAsync(String query, Consumer<Search> onSuccess, Consumer<ApolloException> onError) {
        searchAsync(query, SearchPatternType.STRUCTURAL, onSuccess, onError);
    }

    public void searchAsync(String query, SearchPatternType patternType, Consumer<Search> onSuccess, Consumer<ApolloException> onError) {
        apolloClient.query(new SearchQuery(query, patternType)).enqueue(new ApolloCall.Callback<>() {
            @Override
            public void onResponse(@NotNull Response<SearchQuery.Data> response) {
                List<SearchResult> results = new ArrayList<>();
                Search search = new Search(query, results);
                for (SearchQuery.Result result : response.getData().search().results().results()) {
                    results.addAll(parse(result));
                }

                onSuccess.accept(search);
            }

            @Override
            public void onFailure(@NotNull ApolloException e) {
                onError.accept(e);
            }
        });
    }

    public void searchAsync(String query, Consumer<Search> onSuccess, Consumer<ApolloException> onError) {
        searchAsync(query, SearchPatternType.LITERAL, onSuccess, onError);
    }

    private List<SearchResult> parse(SearchQuery.Result result) {
        if (result instanceof SearchQuery.AsFileMatch) {
            return parse((SearchQuery.AsFileMatch) result);
        }
        if (result instanceof SearchQuery.AsRepository) {
            return parse((SearchQuery.AsRepository) result);
        }
        else {
            throw new IllegalArgumentException("GTFO");
        }
    }

    private List<SearchResult> parse(SearchQuery.AsRepository result) {
        SearchResult searchResult = new SearchResult();
        searchResult.repo = result.name;
        searchResult.type = "repository";
        return Collections.singletonList(searchResult);
    }

    private List<SearchResult> parse(SearchQuery.AsFileMatch result) {
        List<SearchResult> results = new ArrayList<>();

        String repo = result.repository().name();
        String file = result.file().name;
        String path = result.file().path();
        String type = "file";

        for (SearchQuery.LineMatch lineMatch : result.lineMatches()) {
            SearchResult sr = new SearchResult();

            sr.repo = repo;
            sr.file = file;
            sr.type = type;
            sr.preview = lineMatch.preview();
            sr.content = result.file.content;
            sr.offsetAndLength = getOffset(lineMatch);
            sr.lineNumber = lineMatch.lineNumber();
            sr.path = path;
            results.add(sr);
        }

        return results;
    }

    private OffsetAndLength getOffset(SearchQuery.LineMatch lineMatch) {
        for (List<Integer> outer : lineMatch.offsetAndLengths) {
            int offset = outer.get(0);
            int length = outer.get(1);
            return new OffsetAndLength(offset, length);
        }
        return new OffsetAndLength(0, 0);
    }

    public static class Search {
        private String query;
        private List<SearchResult> results;

        public Search(String query, List<SearchResult> results) {
            this.query = query;
            this.results = results;
        }

        public String getQuery() {
            return query;
        }

        public List<SearchResult> getResults() {
            return results;
        }
    }
}