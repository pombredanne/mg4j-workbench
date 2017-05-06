package org.bitfunnel.reproducibility;


import com.martiansoftware.jsap.*;
import it.unimi.di.big.mg4j.index.Index;
import it.unimi.di.big.mg4j.index.TermProcessor;
import it.unimi.di.big.mg4j.query.SelectedInterval;
import it.unimi.di.big.mg4j.query.nodes.QueryBuilderVisitorException;
import it.unimi.di.big.mg4j.query.parser.QueryParserException;
import it.unimi.di.big.mg4j.query.parser.SimpleParser;
import it.unimi.di.big.mg4j.search.DocumentIteratorBuilderVisitor;
import it.unimi.di.big.mg4j.search.score.DocumentScoreInfo;
import it.unimi.dsi.fastutil.objects.Object2ReferenceOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2ObjectMap;
import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.io.output.FileWriterWithEncoding;

import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;


public class QueryLogRunner
{
    private final List<String> queries;
    private int[] matchCounts;
    private long[] timesInNS;
    private boolean[] succeeded;

    private final Index text;
    private final Index title;
    private final Object2ReferenceOpenHashMap<String,Index> indexMap;
    private final Object2ReferenceOpenHashMap<String, TermProcessor> termProcessors;

    ThreadSynchronizer warmupSynchronizer;
    AtomicInteger warmupQueriesRemaining = new AtomicInteger();

    ThreadSynchronizer performanceSynchronizer;
    AtomicInteger performanceQueriesRemaining = new AtomicInteger();

    ThreadSynchronizer finishSynchronizer;

    AtomicBoolean queriesFailed = new AtomicBoolean();

    ArrayList<Thread> threads = new ArrayList<>(16);


    public QueryLogRunner(String basename, String queryLogFile) throws IllegalAccessException, InvocationTargetException, NoSuchMethodException, IOException, InstantiationException, URISyntaxException, ConfigurationException, ClassNotFoundException {
        // Load the query log.
        queries = LoadQueries(Paths.get(queryLogFile));
        matchCounts = new int[queries.size()];
        timesInNS = new long[queries.size()];
        succeeded = new boolean[queries.size()];

        // Load and configure the index.
        text = Index.getInstance( basename + "-text", true, true );
        title = Index.getInstance( basename + "-title", true, true );
//        text = Index.getInstance( basename + "-text?inmemory=1", true, true );
//        title = Index.getInstance( basename + "-title?inmemory=1", true, true );

        indexMap = new Object2ReferenceOpenHashMap<String,Index>(
                new String[] { "text", "title" }, new Index[] { text, title } );
        termProcessors = new Object2ReferenceOpenHashMap<String,TermProcessor>(
                new String[] { "text", "title" },
                new TermProcessor[] { text.termProcessor, title.termProcessor } );;
    }


    void go(int threadCount, Path outfilePath) throws InterruptedException, IOException {
        // Clear out any values from an earlier run.
        for (int i = 0; i < queries.size(); ++i) {
            matchCounts[i] = 0;
            timesInNS[i] = 0;
            succeeded[i] = false;
        }
        queriesFailed.set(false);

        // Set the number of queries for warmup and the test.
        // DESIGN NOTE: never use a value greater than queries.size().
        // For more information, see note in QueryProcessorThread.processLog().
        warmupQueriesRemaining.set(queries.size());
        performanceQueriesRemaining.set(queries.size());
        queriesFailed.set(false);

        warmupSynchronizer = new ThreadSynchronizer(threadCount);
        performanceSynchronizer = new ThreadSynchronizer(threadCount);

        finishSynchronizer = new ThreadSynchronizer(threadCount);

        long startTimeNs = System.nanoTime();

        System.out.println(String.format("Starting %d threads . . .", threadCount));
        for (int i = 0; i < threadCount; ++i) {
            System.out.println(String.format("  thread-%d", i));
            Thread thread = new Thread(new QueryProcessorThread(), String.format("thread-%d", i));
            threads.add(thread);
            thread.start();
        }

        // Wait for last thread to exit.
        System.out.println("Waiting for threads . . .");
        for(int i = 0; i < threads.size(); i++)
            threads.get(i).join();

        long finishTimeNs = System.nanoTime();

        // TODO: write results to a file instead of the console.


        int failedQueriesCount = 0;
        File outFile = outfilePath.toFile();
        BufferedWriter writer =  null;
        try {
            writer = new BufferedWriter(new FileWriterWithEncoding(outFile, StandardCharsets.UTF_8));

            for (int i = 0; i < queries.size(); ++i) {
                if (succeeded[i]) {
                    writer.write(
                            String.format("%s,%d,%f\n",
                                    queries.get(i),
                                    matchCounts[i],
                                    timesInNS[i] * 1e-9));
                } else {
                    ++failedQueriesCount;
                    writer.write(
                            String.format("%s,FAILED,FAILED\n", queries.get(i)));
                }
            }
        }
        finally {
            if (writer != null) {
                writer.close();
            }
        }

        if (queriesFailed.get()) {
            System.out.println(String.format("WARNING: %d queries failed to execute.",
                                             failedQueriesCount));
        }

        double elapsedTime = (finishSynchronizer.startTimeNs - performanceSynchronizer.startTimeNs) * 1e-9;

        System.out.println();
        System.out.println("====================================================");
        System.out.println();
        System.out.println(String.format("Thread count: %d", threadCount));
        System.out.println(String.format("Query count: %d", queries.size()));
        System.out.println(String.format("Total time (synchronizer): %f", elapsedTime));
        System.out.println(String.format("QPS: %f", queries.size() / elapsedTime));
    }


    public static void main( String arg[] ) throws Exception {
        SimpleJSAP jsap = new SimpleJSAP( GenerateBitFunnelChunks.class.getName(), "Builds an index (creates batches, combines them, and builds a term map).",
                new Parameter[] {
                        new FlaggedOption( "threads", JSAP.INTSIZE_PARSER, "1", JSAP.NOT_REQUIRED, 't', "thread-count", "The number of query processing threads." ),
                        new UnflaggedOption( "basename", JSAP.STRING_PARSER, JSAP.REQUIRED, "The index basename." ),
                        new UnflaggedOption( "queries", JSAP.STRING_PARSER, JSAP.REQUIRED, "The query log file. One query per line." ),
                        new UnflaggedOption( "outfile", JSAP.STRING_PARSER, JSAP.REQUIRED, "The output file with match counts and timings for each query." ),
                });

        JSAPResult jsapResult = jsap.parse( arg );
        if ( !jsap.messagePrinted() ) {
            QueryLogRunner runner = new QueryLogRunner(jsapResult.getString( "basename" ), jsapResult.getString( "queries" ));
            runner.go(jsapResult.getInt( "threads" ), Paths.get(jsapResult.getString( "outfile" )));
        }
    }


    public static List<String> LoadQueries(Path path) throws IOException {
        ArrayList<String> list = new ArrayList<String>();

        // DESIGN NOTE: For some reason, Files.lines() leads to the following exception
        // when attempting to read 06.efficiency_topics.all:
        //   java.nio.charset.MalformedInputException: Input length = 1
        // Using slightly more complex code based on FileReader to avoid the exception.

        File file = path.toFile();
        FileReader fileReader = new FileReader(file);
        BufferedReader bufferedReader = new BufferedReader(fileReader);
        String line;
        while ((line = bufferedReader.readLine()) != null) {
            list.add(line);
        }
        fileReader.close();

        return list;
    }


    private class QueryProcessorThread implements Runnable
    {
        private final ExperimentalQueryEngine engine;
        ObjectArrayList<DocumentScoreInfo<Reference2ObjectMap<Index, SelectedInterval[]>>> results;

        private QueryProcessorThread() {
            engine = new ExperimentalQueryEngine(
                new SimpleParser( indexMap.keySet(), "text", termProcessors ),
                new DocumentIteratorBuilderVisitor( indexMap, text, 1000 ),
                indexMap);

            results =
                new ObjectArrayList<DocumentScoreInfo<Reference2ObjectMap<Index,SelectedInterval[]>>>();
        }

        @Override
        public void run() {
            // Process all queries once to "warm up the system".
            try {
                warmupSynchronizer.waitForAllThreadsReady();
                processLog(warmupQueriesRemaining);

                // Record performance measurements on final run.
                performanceSynchronizer.waitForAllThreadsReady();
                processLog(performanceQueriesRemaining);

                finishSynchronizer.waitForAllThreadsReady();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }


        public void processLog(AtomicInteger queriesRemaining)
        {
            while (true) {
                int query = queriesRemaining.decrementAndGet();
                if (query < 0) {
                    break;
                }

                int queryIndex = queries.size() - (query % queries.size()) - 1;

                try {
                    long start = System.nanoTime();
                    results.clear();
                    engine.process(queries.get(queryIndex), 0, 1000000000, results);

                    // DESIGN NOTE: These writes are safe in a multi-threaded environment as long
                    // as two threads never have the same queryIndex. One can guarantee this by
                    // restricting queriesRemaining to values that don't exceed queries.size().
                    // If queriesRemaining is larger, the modulus operation used to compute QueryIndex
                    // could lead to two threads getting assigned the same queryIndex.
                    timesInNS[queryIndex] = System.nanoTime() - start;
                    matchCounts[queryIndex] = results.size();
                    succeeded[queryIndex] = true;
                } catch (QueryParserException e) {
                    succeeded[queryIndex] = false;
                    e.printStackTrace();
                } catch (QueryBuilderVisitorException e) {
                    succeeded[queryIndex] = false;
                    e.printStackTrace();
                } catch (IOException e) {
                    succeeded[queryIndex] = false;
                    e.printStackTrace();
                }
            }
        }
    }
}
