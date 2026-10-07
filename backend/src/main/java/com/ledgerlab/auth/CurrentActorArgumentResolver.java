package com.ledgerlab.auth;

import com.ledgerlab.shared.error.ApiException;
import com.ledgerlab.shared.error.ErrorCode;
import com.ledgerlab.shared.security.CurrentActor;
import com.ledgerlab.shared.security.Role;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/** Resolves {@link CurrentActor} controller parameters from the validated JWT. */
class CurrentActorArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType().equals(CurrentActor.class);
    }

    @Override
    public CurrentActor resolveArgument(
            MethodParameter parameter,
            ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest,
            WebDataBinderFactory binderFactory) {
        return fromSecurityContext();
    }

    static CurrentActor fromSecurityContext() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken token)) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "A valid bearer token is required.");
        }
        Jwt jwt = token.getToken();
        CurrentActor actor = new CurrentActor(
                UUID.fromString(jwt.getSubject()),
                UUID.fromString(jwt.getClaimAsString(TokenService.ORGANIZATION_CLAIM)),
                Role.valueOf(jwt.getClaimAsString(TokenService.ROLE_CLAIM)),
                jwt.getClaimAsString(TokenService.EMAIL_CLAIM));
        MDC.put("organizationId", actor.organizationId().toString());
        MDC.put("userId", actor.userId().toString());
        return actor;
    }
}
