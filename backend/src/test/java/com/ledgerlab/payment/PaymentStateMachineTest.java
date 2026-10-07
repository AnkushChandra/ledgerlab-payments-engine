package com.ledgerlab.payment;

import static com.ledgerlab.payment.PaymentTestData.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ledgerlab.shared.error.ApiException;
import com.ledgerlab.shared.error.ErrorCode;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Exhaustively checks every (status, action) pair: permitted pairs must succeed through the real
 * aggregate, and forbidden pairs must be rejected with INVALID_PAYMENT_STATE without side effects.
 */
class PaymentStateMachineTest {

    /** The specification, written independently of the production table. */
    private static final Map<PaymentStatus, Set<PaymentAction>> SPEC = Map.of(
            PaymentStatus.AUTHORIZED, EnumSet.of(PaymentAction.CAPTURE, PaymentAction.VOID),
            PaymentStatus.PARTIALLY_CAPTURED, EnumSet.of(PaymentAction.CAPTURE),
            PaymentStatus.CAPTURED, EnumSet.of(PaymentAction.REFUND, PaymentAction.OPEN_DISPUTE),
            PaymentStatus.PARTIALLY_REFUNDED, EnumSet.of(PaymentAction.REFUND, PaymentAction.OPEN_DISPUTE),
            PaymentStatus.DISPUTED, EnumSet.of(PaymentAction.RESOLVE_DISPUTE),
            PaymentStatus.VOIDED, EnumSet.noneOf(PaymentAction.class),
            PaymentStatus.REFUNDED, EnumSet.noneOf(PaymentAction.class),
            PaymentStatus.RESOLVED, EnumSet.noneOf(PaymentAction.class),
            PaymentStatus.FAILED, EnumSet.noneOf(PaymentAction.class));

    static Stream<Arguments> everyStatusAndAction() {
        return Stream.of(PaymentStatus.values())
                .flatMap(status -> Stream.of(PaymentAction.values()).map(action -> Arguments.of(status, action)));
    }

    @ParameterizedTest(name = "{0} + {1}")
    @MethodSource("everyStatusAndAction")
    void tableMatchesSpecification(PaymentStatus status, PaymentAction action) {
        assertThat(PaymentStateMachine.isAllowed(status, action))
                .isEqualTo(SPEC.get(status).contains(action));
    }

    @ParameterizedTest(name = "{0} + {1}")
    @MethodSource("everyStatusAndAction")
    void aggregateHonoursTable(PaymentStatus status, PaymentAction action) {
        Payment payment = PaymentTestData.in(status);
        if (SPEC.get(status).contains(action)) {
            apply(payment, action);
            assertThat(payment.getStatus()).isEqualTo(expectedNext(action));
        } else {
            long capturedBefore = payment.getCapturedAmountMinor();
            long refundedBefore = payment.getRefundedAmountMinor();
            assertThatThrownBy(() -> apply(payment, action))
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).code())
                    .isEqualTo(ErrorCode.INVALID_PAYMENT_STATE);
            assertThat(payment.getStatus()).isEqualTo(status);
            assertThat(payment.getCapturedAmountMinor()).isEqualTo(capturedBefore);
            assertThat(payment.getRefundedAmountMinor()).isEqualTo(refundedBefore);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("terminalStatuses")
    void terminalStatusesAllowNothing(PaymentStatus status) {
        assertThat(status.isTerminal()).isTrue();
        assertThat(PaymentStateMachine.allowedActions(status)).isEmpty();
        assertThat(PaymentTestData.in(status).allowedActions()).isEmpty();
    }

    static Stream<PaymentStatus> terminalStatuses() {
        return Stream.of(PaymentStatus.VOIDED, PaymentStatus.REFUNDED, PaymentStatus.RESOLVED, PaymentStatus.FAILED);
    }

    /** Next status for the small amounts used by {@link #apply}. */
    private static PaymentStatus expectedNext(PaymentAction action) {
        return switch (action) {
            case CAPTURE -> PaymentStatus.PARTIALLY_CAPTURED;
            case VOID -> PaymentStatus.VOIDED;
            case REFUND -> PaymentStatus.PARTIALLY_REFUNDED;
            case OPEN_DISPUTE -> PaymentStatus.DISPUTED;
            case RESOLVE_DISPUTE -> PaymentStatus.RESOLVED;
        };
    }

    private static void apply(Payment payment, PaymentAction action) {
        switch (action) {
            case CAPTURE -> payment.capture(1_000, false, NOW);
            case VOID -> payment.voidAuthorization(NOW);
            case REFUND -> payment.refund(500, NOW);
            case OPEN_DISPUTE -> payment.openDispute(500, NOW);
            case RESOLVE_DISPUTE -> payment.resolveDispute(false, NOW);
        }
    }
}
