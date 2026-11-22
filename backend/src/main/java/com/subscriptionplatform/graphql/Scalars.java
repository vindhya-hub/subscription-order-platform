package com.subscriptionplatform.graphql;

import graphql.GraphQLContext;
import graphql.execution.CoercedVariables;
import graphql.language.FloatValue;
import graphql.language.IntValue;
import graphql.language.StringValue;
import graphql.language.Value;
import graphql.schema.Coercing;
import graphql.schema.CoercingParseLiteralException;
import graphql.schema.CoercingParseValueException;
import graphql.schema.CoercingSerializeException;
import graphql.schema.GraphQLScalarType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.function.Function;

/** Custom scalars: {@code Date} (LocalDate), {@code DateTime} (Instant, UTC) and {@code Decimal} (BigDecimal). */
final class Scalars {

    static final GraphQLScalarType DATE = GraphQLScalarType.newScalar()
            .name("Date")
            .description("ISO-8601 calendar date, e.g. 2026-11-22")
            .coercing(new TextCoercing<LocalDate>("an ISO-8601 date", LocalDate.class, LocalDate::parse) {
                @Override
                String format(LocalDate value) {
                    return value.toString();
                }
            })
            .build();

    static final GraphQLScalarType DATE_TIME = GraphQLScalarType.newScalar()
            .name("DateTime")
            .description("ISO-8601 instant in UTC, e.g. 2026-11-22T09:30:00Z")
            .coercing(new TextCoercing<Instant>("an ISO-8601 timestamp", Instant.class,
                    text -> OffsetDateTime.parse(text).toInstant()) {
                @Override
                String format(Instant value) {
                    return value.toString();
                }

                @Override
                Instant convert(Object value) {
                    return value instanceof OffsetDateTime offsetDateTime ? offsetDateTime.toInstant() : super.convert(value);
                }
            })
            .build();

    static final GraphQLScalarType DECIMAL = GraphQLScalarType.newScalar()
            .name("Decimal")
            .description("Exact decimal number serialized as a string to avoid floating-point rounding, e.g. \"31.98\"")
            .coercing(new TextCoercing<BigDecimal>("a decimal number", BigDecimal.class, BigDecimal::new) {
                @Override
                String format(BigDecimal value) {
                    return value.toPlainString();
                }

                @Override
                BigDecimal convert(Object value) {
                    if (value instanceof Number number && !(value instanceof BigDecimal)) {
                        return new BigDecimal(number.toString());
                    }
                    return super.convert(value);
                }

                @Override
                BigDecimal literal(Value<?> input) {
                    if (input instanceof IntValue intValue) {
                        return new BigDecimal(intValue.getValue());
                    }
                    if (input instanceof FloatValue floatValue) {
                        return floatValue.getValue();
                    }
                    return super.literal(input);
                }
            })
            .build();

    private Scalars() {
    }

    /** Coercing for scalars that travel as strings. */
    private abstract static class TextCoercing<T> implements Coercing<T, String> {

        private final String expected;
        private final Class<T> type;
        private final Function<String, T> parser;

        TextCoercing(String expected, Class<T> type, Function<String, T> parser) {
            this.expected = expected;
            this.type = type;
            this.parser = parser;
        }

        abstract String format(T value);

        /** Converts an already-typed Java value; returns null if it is not convertible. */
        T convert(Object value) {
            return type.isInstance(value) ? type.cast(value) : null;
        }

        T literal(Value<?> input) {
            if (input instanceof StringValue stringValue) {
                return parse(stringValue.getValue(), CoercingParseLiteralException::new);
            }
            throw new CoercingParseLiteralException("Expected " + expected + " but got " + input.getClass().getSimpleName());
        }

        @Override
        public String serialize(Object dataFetcherResult, GraphQLContext context, Locale locale) {
            T value = convert(dataFetcherResult);
            if (value == null) {
                throw new CoercingSerializeException("Expected " + expected + " but got " + describe(dataFetcherResult));
            }
            return format(value);
        }

        @Override
        public T parseValue(Object input, GraphQLContext context, Locale locale) {
            if (input instanceof String text) {
                return parse(text, CoercingParseValueException::new);
            }
            T value = convert(input);
            if (value == null) {
                throw new CoercingParseValueException("Expected " + expected + " but got " + describe(input));
            }
            return value;
        }

        @Override
        public T parseLiteral(Value<?> input, CoercedVariables variables, GraphQLContext context, Locale locale) {
            return literal(input);
        }

        @Override
        public Value<?> valueToLiteral(Object input, GraphQLContext context, Locale locale) {
            return StringValue.of(serialize(input, context, locale));
        }

        private T parse(String text, Function<String, RuntimeException> error) {
            try {
                return parser.apply(text.strip());
            } catch (DateTimeParseException | NumberFormatException e) {
                throw error.apply("Expected " + expected + " but got '" + text + "'");
            }
        }

        private static String describe(Object value) {
            return value == null ? "null" : value.getClass().getSimpleName();
        }
    }
}
