package com.ledgerlab.payment;

import com.ledgerlab.shared.error.ApiException;
import com.ledgerlab.shared.error.ErrorCode;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The single source of truth for which actions are permitted in which payment status. The
 * resulting status of an action depends on amounts and is computed by {@link Payment}; this class
 * only answers "may this action be attempted now?".
 */
public final class PaymentStateMachine {

    private static final Map<PaymentStatus, EnumSet<PaymentAction>> ALLOWED = new EnumMap<>(PaymentStatus.class);

    static {
        for (PaymentStatus status : PaymentStatus.values()) {
            ALLOWED.put(status, EnumSet.noneOf(PaymentAction.class));
        }
        ALLOWED.put(PaymentStatus.AUTHORIZED, EnumSet.of(PaymentAction.CAPTURE, PaymentAction.VOID));
        ALLOWED.put(PaymentStatus.PARTIALLY_CAPTURED, EnumSet.of(PaymentAction.CAPTURE));
        ALLOWED.put(PaymentStatus.CAPTURED, EnumSet.of(PaymentAction.REFUND, PaymentAction.OPEN_DISPUTE));
        ALLOWED.put(PaymentStatus.PARTIALLY_REFUNDED, EnumSet.of(PaymentAction.REFUND, PaymentAction.OPEN_DISPUTE));
        ALLOWED.put(PaymentStatus.DISPUTED, EnumSet.of(PaymentAction.RESOLVE_DISPUTE));
    }

    private PaymentStateMachine() {}

    public static boolean isAllowed(PaymentStatus status, PaymentAction action) {
        return ALLOWED.get(status).contains(action);
    }

    public static Set<PaymentAction> allowedActions(PaymentStatus status) {
        return EnumSet.copyOf(ALLOWED.get(status));
    }

    public static void require(PaymentStatus status, PaymentAction action) {
        if (!isAllowed(status, action)) {
            throw new ApiException(
                    ErrorCode.INVALID_PAYMENT_STATE,
                    "Cannot " + action.name().toLowerCase().replace('_', ' ') + " a payment in status " + status + ".");
        }
    }
}
