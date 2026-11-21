package history.loader;

import history.History;
import history.Operation;
import lombok.SneakyThrows;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.HashMap;
import java.util.regex.Pattern;

public class TextHistoryLoader implements HistoryLoader<Long, Long> {
    static final String patternString = "^(r|w)\\((\\d++),(\\d++),(\\d++),(-?\\d++)\\)$";
    static final Pattern regex = Pattern.compile(patternString);

    @Override
    @SneakyThrows
    public History<Long, Long> loadHistory(String path) {
        try (var in = new BufferedReader(new FileReader(path))) {
            var history = new History<Long, Long>();
            var sessionIdMap = new HashMap<Long, Long>();
            long minSessionId = 0;
            long lineNumber = 0;
            String line;

            while ((line = in.readLine()) != null) {
                lineNumber++;
                var match = regex.matcher(line);
                if (!match.matches()) {
                    throw new IllegalArgumentException("Invalid history format at line " + lineNumber + ": " + line);
                }

                var op = match.group(1);
                var key = Long.parseLong(match.group(2));
                var value = Long.parseLong(match.group(3));
                var session = Long.parseLong(match.group(4));
                if (sessionIdMap.containsKey(session)) {
                    session = sessionIdMap.get(session);
                } else {
                    sessionIdMap.put(session, minSessionId);
                    session = minSessionId++;
                }
                var txn = Long.parseLong(match.group(5));

                // txn == -1 => aborted
                if (txn == -1) {
                    if (op.equals("w")) {
                        history.addAbortedWrite(key, value);
                    }
                    continue;
                }

                if (history.getSession(session) == null) {
                    history.addSession(session);
                }

                if (history.getTransaction(txn) == null) {
                    history.addTransaction(history.getSession(session), txn);
                }

                history.addOperation(history.getTransaction(txn),
                        op.equals("r") ? Operation.Type.READ : Operation.Type.WRITE, key,
                        value);
            }

            return history;
        }
    }
}
