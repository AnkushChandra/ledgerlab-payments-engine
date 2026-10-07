package com.ledgerlab.payment;

import static com.ledgerlab.payment.PaymentTestData.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ledgerlab.shared.error.ApiException;
import com.ledgerlab.shared.error.ErrorCode;
import org.junit.jupiter.api.Test;

class PaymentTest {

    @Test
    void fullCaptureMovesToCaptured() {
        Payment payment = PaymentTestData.authorized(10_000);
        Payment.CaptureResult result = payment.capture(10_000, false, NOW);
        assertThat(result.capturedMinor()).isEqualTo(10_000);
        assertThat(result.releasedMinor()).isZero();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(payment.getFirstCapturedAt()).isEqualTo(NOW);
    }

    @Test
    void partialCapturesAccumulateUntilAuthorizationIsExhausted() {
        Payment payment = PaymentTestData.authorized(10_000);
        payment.capture(3_000, false, NOW);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PARTIALLY_CAPTURED);
        payment.capture(7_000, false, NOW.plusSeconds(60));
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(payment.getCapturedAmountMinor()).isEqualTo(10_000);
        assertThat(payment.getFirstCapturedAt()).isEqualTo(NOW);
    }

    @Test
    void captureCannotExceedRemainingAuthorization() {
        Payment payment = PaymentTestData.authorized(10_000);
        payment.capture(6_000, false, NOW);
        assertThatThrownBy(() -> payment.capture(4_001, false, NOW))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.CAPTURE_EXCEEDS_AUTHORIZATION);
        assertThat(payment.getCapturedAmountMinor()).isEqualTo(6_000);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PARTIALLY_CAPTURED);
    }

    @Test
    void finalCaptureReleasesTheRemainder() {
        Payment payment = PaymentTestData.authorized(10_000);
        Payment.CaptureResult result = payment.capture(2_500, true, NOW);
        assertThat(result.releasedMinor()).isEqualTo(7_500);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(payment.remainingAuthorizationMinor()).isZero();
        assertThat(payment.refundableMinor()).isEqualTo(2_500);
    }

    @Test
    void voidReleasesTheWholeAuthorization() {
        Payment payment = PaymentTestData.authorized(10_000);
        assertThat(payment.voidAuthorization(NOW)).isEqualTo(10_000);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.VOIDED);
        assertThat(payment.allowedActions()).isEmpty();
    }

    @Test
    void voidIsRejectedOnceAnyAmountWasCaptured() {
        Payment payment = PaymentTestData.authorized(10_000);
        payment.capture(1, false, NOW);
        assertThatThrownBy(() -> payment.voidAuthorization(NOW))
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.INVALID_PAYMENT_STATE);
    }

    @Test
    void refundsAccumulateUpToCapturedAmount() {
        Payment payment = PaymentTestData.authorized(10_000);
        payment.capture(8_000, true, NOW);
        payment.refund(3_000, NOW);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
        payment.refund(5_000, NOW);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(payment.netSettledMinor()).isZero();
    }

    @Test
    void refundCannotExceedRefundableAmount() {
        Payment payment = PaymentTestData.authorized(10_000);
        payment.capture(10_000, false, NOW);
        payment.refund(9_000, NOW);
        assertThatThrownBy(() -> payment.refund(1_001, NOW))
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.REFUND_EXCEEDS_CAPTURED);
        assertThat(payment.getRefundedAmountMinor()).isEqualTo(9_000);
    }

    @Test
    void lostDisputeIsChargedBackAndClosesThePayment() {
        Payment payment = PaymentTestData.authorized(10_000);
        payment.capture(10_000, false, NOW);
        payment.refund(2_000, NOW);
        payment.openDispute(8_000, NOW);
        payment.resolveDispute(false, NOW);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.RESOLVED);
        assertThat(payment.getDisputeLostAmountMinor()).isEqualTo(8_000);
        assertThat(payment.netSettledMinor()).isZero();
    }

    @Test
    void wonDisputeKeepsMerchantFunds() {
        Payment payment = PaymentTestData.authorized(10_000);
        payment.capture(10_000, false, NOW);
        payment.openDispute(10_000, NOW);
        payment.resolveDispute(true, NOW);
        assertThat(payment.getDisputeLostAmountMinor()).isZero();
        assertThat(payment.netSettledMinor()).isEqualTo(10_000);
    }

    @Test
    void disputeCannotExceedRefundableAmount() {
        Payment payment = PaymentTestData.authorized(10_000);
        payment.capture(10_000, false, NOW);
        payment.refund(4_000, NOW);
        assertThatThrownBy(() -> payment.openDispute(6_001, NOW))
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.DISPUTE_EXCEEDS_REFUNDABLE);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
    }

    @Test
    void allowedActionsReflectAmounts() {
        assertThat(PaymentTestData.in(PaymentStatus.AUTHORIZED).allowedActions())
                .containsExactlyInAnyOrder(PaymentAction.CAPTURE, PaymentAction.VOID);
        assertThat(PaymentTestData.in(PaymentStatus.CAPTURED).allowedActions())
                .containsExactlyInAnyOrder(PaymentAction.REFUND, PaymentAction.OPEN_DISPUTE);
        assertThat(PaymentTestData.in(PaymentStatus.DISPUTED).allowedActions())
                .containsExactly(PaymentAction.RESOLVE_DISPUTE);
    }
}
