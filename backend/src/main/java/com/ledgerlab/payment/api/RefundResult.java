package com.ledgerlab.payment.api;

public record RefundResult(RefundResponse refund, PaymentResponse payment) {}
