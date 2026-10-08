package com.healthtech.doctor.config;

import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;

import java.util.Set;

/**
 * Adds the error responses every operation shares, so controllers only declare the ones specific to them:
 * 400 for operations that take input, 401/403 for operations behind the bearer scheme, and the
 * {@code ProblemDetail} body on the error codes the exception handlers produce it for.
 */
@Component
public class OpenApiErrorCustomizer implements OperationCustomizer {
    static final String PROBLEM_DETAIL = "ProblemDetail";
    private static final String PROBLEM_JSON = "application/problem+json";
    // Codes whose body is always a ProblemDetail. For 401/403, Spring Security's own responses (missing token,
    // wrong role) have an empty body, so those are only given a ProblemDetail when a controller declared them
    // (login 401, ownership 403); springdoc then fills in the success schema, which is replaced here.
    private static final Set<String> PROBLEM_CODES = Set.of("400", "404", "409");

    static Schema<?> problemDetailSchema() {
        return new ObjectSchema()
                .description("RFC 9457 problem details returned by the exception handlers")
                .addProperty("type", new StringSchema().example("about:blank"))
                .addProperty("title", new StringSchema().example("Validation Error"))
                .addProperty("status", new Schema<Integer>().type("integer").example(400))
                .addProperty("detail", new StringSchema().example("Validation failed"))
                .addProperty("instance", new StringSchema().example("/api/auth/register"))
                .addProperty("errors", new ObjectSchema()
                        .description("Field name to message; only present on validation failures")
                        .additionalProperties(new StringSchema()));
    }

    @Override
    public Operation customize(Operation operation, HandlerMethod handlerMethod) {
        ApiResponses responses = operation.getResponses();
        boolean secured = operation.getSecurity() == null || !operation.getSecurity().isEmpty();
        boolean takesInput = operation.getRequestBody() != null
                || (operation.getParameters() != null && !operation.getParameters().isEmpty());

        if (takesInput && !responses.containsKey("400")) {
            responses.addApiResponse("400", new ApiResponse().description("Validation failed or malformed request"));
        }
        if (secured) {
            responses.putIfAbsent("401", new ApiResponse().description("Missing, invalid or expired token"));
            responses.putIfAbsent("403", new ApiResponse().description("The token does not belong to a doctor"));
        }
        responses.forEach((code, response) -> {
            boolean declaredAuthError = (code.equals("401") || code.equals("403")) && response.getContent() != null;
            if (PROBLEM_CODES.contains(code) || declaredAuthError) {
                attachProblemBody(response);
            }
        });
        return operation;
    }

    private static void attachProblemBody(ApiResponse response) {
        response.setContent(new Content().addMediaType(PROBLEM_JSON,
                new MediaType().schema(new Schema<>().$ref("#/components/schemas/" + PROBLEM_DETAIL))));
    }
}
