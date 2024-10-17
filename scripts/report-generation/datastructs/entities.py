from datastructs.report import CIsReportInfo, CompletedPaymentsReportInfo, NotCompletedPaymentsReportInfo, ReceiptDetailStatistics, TriggerPrimitiveReportInfo


class ReportEntity:

    def __init__(self, 
                 id, 
                 date, 
                 total_payments_on_ndp,
                 creditor_institutions: CIsReportInfo,
                 trigger_primitive: TriggerPrimitiveReportInfo,
                 completed_payments: CompletedPaymentsReportInfo,
                 not_completed_payments: NotCompletedPaymentsReportInfo):
        # generating map from objects
        self.data = {
            "id": id,
            "date": date,
            "creditor_institution_info": {
                "total": creditor_institutions.cis_total,
                "on_wisp": creditor_institutions.cis_on_wisp,
            },
            "payments": {
                "total_on_ndp": total_payments_on_ndp,
                "total_on_wisp": trigger_primitive.carts_total + trigger_primitive.no_carts_total,
                "trigger_primitives": {
                    "total_carts": trigger_primitive.carts_total,
                    "total_no_carts": trigger_primitive.no_carts_total,
                    "carts_completed": trigger_primitive.carts_completed,
                    "no_carts_completed": trigger_primitive.no_carts_completed,
                },
                "completed": {
                    "ok_receipts": completed_payments.closed_as_ok,
                    "ko_receipts": completed_payments.closed_as_ko,
                    "ok_receipt_sent_by_retry": completed_payments.ok_receipt_sent_by_retry,
                    "ko_receipt_sent_by_retry": completed_payments.ko_receipt_sent_by_retry,
                },
                "not_completed": {
                    "rejected": not_completed_payments.rejected.to_dict(),
                    "never_sent": not_completed_payments.never_sent.to_dict(),
                    "scheduled": not_completed_payments.scheduled.to_dict(),
                    "blocked": not_completed_payments.blocked.to_dict()
                }
            }
        }


    def get_map(self):
        return self.data
    

    def from_db_item(item):

        # extract useful data from item map
        item_ci_info = item["creditor_institution_info"]
        payments_info = item["payments"]
        trigger_primitive_info = payments_info["trigger_primitives"]
        completed_payment_info = payments_info["completed"]
        not_completed_payment_info = payments_info["not_completed"]
        rejected_payments_info = not_completed_payment_info["rejected"]
        never_sent_payments_info = not_completed_payment_info["never_sent"]
        scheduled_payments_info = not_completed_payment_info["scheduled"]
        blocked_payments_info = not_completed_payment_info["blocked"]
        
        # generate complex objects
        creditor_institutions = CIsReportInfo(cis_total=item_ci_info["total"], 
                                              cis_on_wisp=item_ci_info["on_wisp"])
        trigger_primitives = TriggerPrimitiveReportInfo(carts_total=trigger_primitive_info["total_carts"], 
                                                        no_carts_total=trigger_primitive_info["total_no_carts"],
                                                        carts_completed=trigger_primitive_info["carts_completed"],
                                                        no_carts_completed=trigger_primitive_info["no_carts_completed"])
        completed_payment_info = CompletedPaymentsReportInfo(closed_as_ok=completed_payment_info["ok_receipts"],
                                                            closed_as_ko=completed_payment_info["ko_receipts"],
                                                            ok_receipt_sent_by_retry=completed_payment_info["ok_receipt_sent_by_retry"],
                                                            ko_receipt_sent_by_retry=completed_payment_info["ko_receipt_sent_by_retry"])
        not_completed_payment_info = NotCompletedPaymentsReportInfo(rejected=ReceiptDetailStatistics(count_ok=rejected_payments_info["count_ok"],
                                                                                                     count_ko=rejected_payments_info["count_ko"]), 
                                                                    never_sent=ReceiptDetailStatistics(count_ok=never_sent_payments_info["count_ok"],
                                                                                                       count_ko=never_sent_payments_info["count_ko"]), 
                                                                    scheduled=ReceiptDetailStatistics(count_ok=scheduled_payments_info["count_ok"],
                                                                                                      count_ko=scheduled_payments_info["count_ko"]), 
                                                                    blocked=ReceiptDetailStatistics(count_ok=blocked_payments_info["count_ok"],
                                                                                                    count_ko=blocked_payments_info["count_ko"]))

        # generate report entity 
        return ReportEntity(id=item["id"],
                            date=item["date"],
                            total_payments_on_ndp=payments_info["total_on_ndp"],
                            creditor_institutions=creditor_institutions,
                            trigger_primitive=trigger_primitives,
                            completed_payments=completed_payment_info,
                            not_completed_payments=not_completed_payment_info)


# ============================================================================
     
class ReceiptRTEntity:

    def __init__(self, id, status, type):
        self.id = id
        self.status = status
        self.type = type
    

    def from_db_item(item):
        return ReceiptRTEntity(item["id"], 
                               item["receiptStatus"], 
                               item["receiptType"])
    

# ============================================================================
     
class REEventEntity:

    def __init__(self, 
                 id, 
                 partition_key,
                 session_id,
                 business_process,
                 status, 
                 operation_id, 
                 event_category, 
                 event_subcategory):
        self.id = id
        self.partition_key = partition_key
        self.session_id = session_id
        self.business_process = business_process
        self.status = status
        self.operation_id = operation_id 
        self.event_category = event_category 
        self.event_subcategory = event_subcategory
    

    def from_db_item(item):
        return REEventEntity(id=item["id"],
                             partition_key=item["partitionKey"], 
                             session_id=item["sessionId"], 
                             business_process=item["businessProcess"],
                             status=item["status"],
                             operation_id=item["operationId"],
                             event_category=item["eventCategory"],
                             event_subcategory=item["eventSubcategory"])
 

# ============================================================================
     
class TriggerPrimitiveEntity:

    def __init__(self, session_id, primitive, payload):
        self.session_id = session_id
        self.primitive = primitive
        self.payload = payload
    

    def from_db_item(item):
        return TriggerPrimitiveEntity(item["id"], item["primitive"], item["payload"])
