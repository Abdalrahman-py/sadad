package com.sadad.app;

import java.util.HashMap;
import java.util.Map;

/**
 * The ledger's refusal codes as sentences a storekeeper can act on.
 *
 * <p>The server answers an operation with {@code {status:"rejected", code}} and no message on that
 * path, so the wording has to live here too. The codes are the ledger's own — the same ones the
 * server's {@code sadid_private.apply} raises and the same ones it maps for HTTP errors in
 * {@code sadid-ledger/index.ts} — so a refusal reads the same whichever layer produced it.
 */
final class LedgerMessages {
    private static final Map<String, String> MESSAGES = new HashMap<>();

    static {
        MESSAGES.put("duplicate_phone", "رقم الهاتف مسجل عند شخص آخر في هذا المتجر.");
        MESSAGES.put("invalid_phone", "رقم الهاتف غير صالح؛ اكتب رقمًا دوليًا كاملًا أو اتركه فارغًا.");
        MESSAGES.put("contact_limit_reached", "وصل المتجر إلى حد عدد الأشخاص.");
        MESSAGES.put("contact_unavailable", "الشخص غير متاح أو مؤرشف.");
        MESSAGES.put("debt_unavailable", "الدين غير موجود على الخادم.");
        MESSAGES.put("payment_unavailable", "الدفعة غير موجودة على الخادم.");
        MESSAGES.put("overpayment", "الدفعة تتجاوز المتبقي على الدين.");
        MESSAGES.put("invalid_money", "المبلغ غير صالح.");
        MESSAGES.put("invalid_input", "بيانات الإدخال غير صالحة.");
        MESSAGES.put("invalid_timestamp", "وقت الإدخال غير صالح؛ راجع ساعة الجهاز.");
        MESSAGES.put("entity_id_exists", "رقم هذا السجل مستخدم مسبقًا.");
        MESSAGES.put("entity_version_changed", "تغيّر السجل على الخادم منذ آخر تحديث؛ حدّث السجلات وأعد المحاولة.");
        MESSAGES.put("reason_required", "اكتب سببًا واضحًا لهذا الإجراء.");
        MESSAGES.put("already_reversed", "هذه الدفعة ملغاة مسبقًا.");
        MESSAGES.put("below_paid_total", "لا يمكن أن يقل أصل الدين عن مجموع الدفعات المسجلة.");
        MESSAGES.put("unsupported_operation", "هذا الإجراء غير مدعوم في هذا الإصدار.");
        MESSAGES.put("dependency_rejected", "الإدخال الذي يعتمد عليه هذا الإدخال مرفوض؛ صحّحه أولًا.");
        MESSAGES.put("dependency_pending", "انتظر: الإدخال الذي يعتمد عليه هذا الإدخال لم يصل بعد.");
        MESSAGES.put("operation_id_reused", "رقم العملية مستخدم بمحتوى مختلف.");
        MESSAGES.put("duplicate_operation_id", "رقم العملية مكرر في قائمة الاسترداد.");
        MESSAGES.put("unsupported_schema", "إصدار الطلب غير مدعوم؛ حدّث التطبيق.");
        MESSAGES.put("store_permission_denied", "لا توجد صلاحية لهذا الإجراء على هذا الجهاز.");
        MESSAGES.put("installation_revoked", "تم إلغاء هذا الجهاز؛ اطلب اعتمادًا جديدًا من الإدارة.");
        MESSAGES.put("installation_key_changed", "مفتاح الجهاز مختلف؛ يلزم تسجيل تركيب جديد.");
        MESSAGES.put("session_installation_conflict", "الجلسة مرتبطة بجهاز آخر؛ سجّل الدخول من جديد.");
        MESSAGES.put("ledger_not_initialized", "لم يُجهَّز دفتر المتجر بعد؛ انتظر اعتماد الجهاز من الإدارة.");
        MESSAGES.put("epoch_changed", "تغيّر إصدار استرداد الخادم؛ افحص الإيصالات مع الإدارة ثم أعد الإرسال.");
        MESSAGES.put("recovery_not_authorized", "هذا الإدخال غير مشمول بتصريح استرداد صالح.");
        MESSAGES.put("authorization_required", "انتهت صلاحية الطلب؛ سجّل الدخول من جديد.");
        MESSAGES.put("store_suspended", "المتجر موقوف؛ حُفظت الإدخالات المعلقة.");
        MESSAGES.put("password_change_required", "غيّر كلمة المرور المؤقتة أولًا.");
        MESSAGES.put("request_rate_limited", "طلبات كثيرة؛ الإدخالات محفوظة، انتظر دقيقة ثم حاول مجددًا.");
    }

    private LedgerMessages() { }

    /** The sentence for a code, or the code itself when it is one this build does not know. */
    static String of(String code) {
        if (code == null || code.isEmpty()) return "رفض الخادم الإدخال.";
        String message = MESSAGES.get(code);
        return message == null ? code : message;
    }
}
