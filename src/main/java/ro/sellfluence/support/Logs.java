package ro.sellfluence.support;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

public class Logs {

    private static final Formatter defaultFormatter = new Formatter() {
        @Override
        public String format(LogRecord record) {
            String output;
            if (record.getThrown() == null) {
                output = ("%s %-10s %s (%s.%s)%n").formatted(
                        DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(record.getInstant().atZone(ZoneId.systemDefault())),
                        record.getLevel(),
                        record.getMessage(),
                        record.getSourceClassName(),
                        record.getSourceMethodName()
                );
            } else {
                output = ("%s %-10s %s (%s.%s)%n%s%n").formatted(
                        DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(record.getInstant().atZone(ZoneId.systemDefault())),
                        record.getLevel(),
                        record.getMessage(),
                        record.getSourceClassName(),
                        record.getSourceMethodName(),
                        record.getThrown()
                );
            }
            return output;
        }
    };

    public static Logger getConsoleLogger(String name, Level level) {
        Logger logger = getLogger(name, level);
        addHandlerWithDefaultFormatter(logger, new ConsoleHandler());
        logger.setUseParentHandlers(false);
        return logger;
    }

    public static Logger getConsoleAndFileLogger(String name, Level level, int generations, long maxFileSize) {
        Logger logger = getConsoleLogger(name, level);
        try {
            addFileHandler(logger, makePattern(name), generations, maxFileSize);
        } catch (IOException e) {
            Logger.getGlobal().log(Level.WARNING, "Could not create a file handler for logger " + name + " thus logging only to the console.");
        }
        return logger;
    }

    public static Logger getFileLogger(String name, Level level, int generations, long maxFileSize) {
        Logger logger = getLogger(name, level);
        try {
            addFileHandler(logger, makePattern(name), generations, maxFileSize);
        } catch (IOException e) {
            logger.setUseParentHandlers(true);
            Logger.getGlobal().log(Level.WARNING, "Could not create a file handler for logger " + name + " which therefore will use the default handler.");
        }
        return logger;
    }

    private static @NotNull String makePattern(String name) {
        return "%t/" + name + "_%g.log";
    }

    private static void addFileHandler(Logger logger, String pattern, int generations, long maxFileSize) throws IOException {
        FileHandler handler = new FileHandler(pattern, maxFileSize, generations, false);
        addHandlerWithDefaultFormatter(logger, handler);
    }

    private static void addHandlerWithDefaultFormatter(Logger logger, Handler handler) {
        handler.setFormatter(defaultFormatter);
        logger.addHandler(handler);
        logger.setUseParentHandlers(false);
    }

    private static @NotNull Logger getLogger(String name, Level level) {
        Logger logger = Logger.getLogger(name);
        logger.setLevel(level);
        return logger;
    }
}