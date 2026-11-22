package com.subscriptionplatform.graphql;

import com.subscriptionplatform.common.BusinessRuleException;
import com.subscriptionplatform.common.NotFoundException;
import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.schema.DataFetchingEnvironment;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.util.stream.Collectors;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.stereotype.Component;

/** Maps domain exceptions to GraphQL errors with a meaningful classification instead of INTERNAL_ERROR. */
@Component
public class GraphQlExceptionResolver extends DataFetcherExceptionResolverAdapter {

    @Override
    protected GraphQLError resolveToSingleError(Throwable ex, DataFetchingEnvironment env) {
        if (ex instanceof NotFoundException) {
            return error(env, ErrorType.NOT_FOUND, ex.getMessage());
        }
        if (ex instanceof BusinessRuleException) {
            return error(env, ErrorType.BAD_REQUEST, ex.getMessage());
        }
        if (ex instanceof ConstraintViolationException violations) {
            String message = violations.getConstraintViolations().stream()
                    .map(GraphQlExceptionResolver::describe)
                    .sorted()
                    .collect(Collectors.joining("; "));
            return error(env, ErrorType.BAD_REQUEST, message);
        }
        if (ex instanceof OptimisticLockingFailureException) {
            return error(env, ErrorType.BAD_REQUEST, "The record was modified concurrently; please retry");
        }
        return null; // fall back to the default INTERNAL_ERROR handling
    }

    private static GraphQLError error(DataFetchingEnvironment env, ErrorType type, String message) {
        return GraphqlErrorBuilder.newError(env).errorType(type).message(message).build();
    }

    /** "createSubscription.input.quantity" -> "input.quantity: must be less than or equal to 100". */
    private static String describe(ConstraintViolation<?> violation) {
        String path = violation.getPropertyPath().toString();
        int firstDot = path.indexOf('.');
        String field = firstDot >= 0 ? path.substring(firstDot + 1) : path;
        return field + ": " + violation.getMessage();
    }
}
