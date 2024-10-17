from datastructs.dtos import ReceiptDetailStatistics
from utility.constants import Constants 
from utility.utility import Utility 
from datastructs.configuration import Configuration


class ReportNumericData:

    def __init__(self):
        self.creditor_institutions = CIsReportInfo()
        self.total_payments_on_ndp = 0
        self.trigger_primitives = TriggerPrimitiveReportInfo()
        self.completed_payments = CompletedPaymentsReportInfo()
        self.not_completed_payments = NotCompletedPaymentsReportInfo()
   

# ============================================================================
   
class CIsReportInfo:

    def __init__(self, cis_total=0, cis_on_wisp=0):
        self.cis_total = cis_total
        self.cis_on_wisp = cis_on_wisp
 

    def merge(self, report_info):
        self.cis_total += report_info.cis_total
        self.cis_on_wisp += report_info.cis_on_wisp
    
    def extract_from_report_entity(report_entity):
        report_data = report_entity.get_map()
        creditor_institution_info = report_data["creditor_institution_info"]
        return CIsReportInfo(cis_total=creditor_institution_info["total"], 
                             cis_on_wisp=creditor_institution_info["on_wisp"])


# ============================================================================
   
class TriggerPrimitiveReportInfo:

    def __init__(self, carts_completed=0, carts_total=0, no_carts_completed=0, no_carts_total=0):
        self.carts_completed = carts_completed
        self.carts_total = carts_total
        self.no_carts_completed = no_carts_completed
        self.no_carts_total = no_carts_total


    def merge(self, report_info):
        self.carts_completed += report_info.carts_completed
        self.carts_total += report_info.carts_total
        self.no_carts_completed += report_info.no_carts_completed
        self.no_carts_total += report_info.no_carts_total


    def extract_from_report_entity(report_entity):
        report_data = report_entity.get_map()
        payments_info = report_data["payments"]
        trigger_primitive_info = payments_info["trigger_primitives"]
        return TriggerPrimitiveReportInfo(carts_total=trigger_primitive_info["total_carts"],
                                          no_carts_total=trigger_primitive_info["total_no_carts"],
                                          carts_completed=trigger_primitive_info["carts_completed"],
                                          no_carts_completed=trigger_primitive_info["no_carts_completed"])

# ============================================================================

class CompletedPaymentsReportInfo:

    def __init__(self, 
                 closed_as_ok=0, 
                 closed_as_ko=0, 
                 ok_receipt_sent_by_retry=0,
                 ko_receipt_sent_by_retry=0):
        self.closed_as_ok = closed_as_ok
        self.closed_as_ko = closed_as_ko
        self.ok_receipt_sent_by_retry = ok_receipt_sent_by_retry
        self.ko_receipt_sent_by_retry = ko_receipt_sent_by_retry


    def merge(self, report_info):
        self.closed_as_ok += report_info.closed_as_ok
        self.closed_as_ko += report_info.closed_as_ko
        self.ok_receipt_sent_by_retry += report_info.ok_receipt_sent_by_retry
        self.ko_receipt_sent_by_retry += report_info.ko_receipt_sent_by_retry


    def extract_from_report_entity(report_entity):
        report_data = report_entity.get_map()
        payments_info = report_data["payments"]
        completed_payments_info = payments_info["completed"]
        return CompletedPaymentsReportInfo(closed_as_ok=completed_payments_info["ok_receipts"],
                                    closed_as_ko=completed_payments_info["ko_receipts"],
                                    ok_receipt_sent_by_retry=completed_payments_info["ok_receipt_sent_by_retry"],
                                    ko_receipt_sent_by_retry=completed_payments_info["ok_receipt_sent_by_retry"])


# ============================================================================

class NotCompletedPaymentsReportInfo:

    def __init__(self,
                 rejected: ReceiptDetailStatistics = ReceiptDetailStatistics(),
                 never_sent: ReceiptDetailStatistics = ReceiptDetailStatistics(),
                 scheduled: ReceiptDetailStatistics = ReceiptDetailStatistics(),
                 blocked: ReceiptDetailStatistics = ReceiptDetailStatistics()):
        self.rejected = rejected
        self.never_sent = never_sent
        self.scheduled = scheduled
        self.blocked = blocked


    def merge(self, report_info):
        self.rejected.merge(report_info.rejected)
        self.never_sent.merge(report_info.never_sent)
        self.scheduled.merge(report_info.scheduled)
        self.blocked.merge(report_info.blocked)


    def extract_from_report_entity(report_entity):
        report_data = report_entity.get_map()
        payments_info = report_data["payments"]
        not_completed_payments_info = payments_info["not_completed"]
        rejected_payments_info = not_completed_payments_info["rejected"]
        never_sent_payments_info = not_completed_payments_info["never_sent"]
        scheduled_payments_info = not_completed_payments_info["scheduled"]
        blocked_payments_info = not_completed_payments_info["blocked"]
        return NotCompletedPaymentsReportInfo(rejected=ReceiptDetailStatistics(count_ok=rejected_payments_info["count_ok"],
                                                                               count_ko=rejected_payments_info["count_ko"]),
                                              never_sent=ReceiptDetailStatistics(count_ok=never_sent_payments_info["count_ok"],
                                                                                 count_ko=never_sent_payments_info["count_ko"]),
                                              scheduled=ReceiptDetailStatistics(count_ok=scheduled_payments_info["count_ok"],
                                                                                count_ko=scheduled_payments_info["count_ko"]),
                                              blocked=ReceiptDetailStatistics(count_ok=blocked_payments_info["count_ok"],
                                                                              count_ko=blocked_payments_info["count_ko"]))



# ============================================================================
   
class Report:

    def __init__(self, date, configuration: Configuration):
        self.date = date
        self.configuration = configuration
        self.numeric_data = ReportNumericData()


    def populate_with_data(self, numeric_data: ReportNumericData):
        self.numeric_data = numeric_data


# ============================================================================
     
class ReportNotificationDetail:

    def __init__(self, date):
        self.date = date
        self.notes = Constants.NODATA
        self.general = Constants.NODATA
        self.payments = Constants.NODATA
    

    def extract_details_for_general_info(self, 
                                         creditor_institution_info: CIsReportInfo, 
                                         trigger_primitive_info : TriggerPrimitiveReportInfo, 
                                         payments_on_ndp = 0):

        cis_total = creditor_institution_info.cis_total
        cis_on_wisp = creditor_institution_info.cis_on_wisp
        percentage_cis_on_wisp = Utility.safe_divide(cis_on_wisp * 100, cis_total)

        payments_on_wisp = trigger_primitive_info.carts_total +  trigger_primitive_info.no_carts_total
        total_payments = payments_on_wisp + payments_on_ndp
        percentage_payments_on_wisp = Utility.safe_divide(payments_on_wisp * 100, total_payments)
        percentage_payments_on_ndp = Utility.safe_divide(payments_on_ndp * 100, total_payments)

        self.notes = f'''
        \n>:information_source: _*Nota:*_ \n> _Di seguito si fa riferimento *solo* alle primitive *nodoInviaRPT* e *nodoInviaCarrelloRPT*._ \n>\n> _Per ricevuta si fa riferimento alla *paaInviaRT* inviata all'ente. Possono essere generate più ricevute per uno stesso pagamento innescato, una per ogni singola richiesta pagamento telematico._
        '''

        self.general = f'''
        \n*:post_office: Numero totale di enti censiti:* `{cis_total}` \n   di cui con almeno una stazione su Dismissione WISP: `{cis_on_wisp}` (`{percentage_cis_on_wisp:.2f}%`)
        \n*:pagopa-piattaforma: Numero totale di pagamenti su piattaforma pagoPA:* `{total_payments}` \n   di cui gestiti da Nodo: `{payments_on_ndp}` (`{percentage_payments_on_ndp:.2f}%`) \n   di cui gestiti da Dismissione WISP: `{payments_on_wisp}` (`{percentage_payments_on_wisp:.2f}%`)
        '''
        

    def extract_detail_for_payments(self, 
                                    trigger_primitive_info: TriggerPrimitiveReportInfo,
                                    completed_payments_info: CompletedPaymentsReportInfo,
                                    not_completed_payments_info: NotCompletedPaymentsReportInfo):
                
        # Divided by trigger primitives on D-WISP
        carts_triggered = trigger_primitive_info.carts_total
        no_carts_triggered = trigger_primitive_info.no_carts_total
        total_triggered = carts_triggered + no_carts_triggered
        percentage_carts_triggered = Utility.safe_divide(carts_triggered * 100, total_triggered)
        percentage_no_carts_triggered = Utility.safe_divide(no_carts_triggered * 100, total_triggered)

        #
        completed_carts_triggered = trigger_primitive_info.carts_completed
        completed_no_carts_triggered = trigger_primitive_info.no_carts_completed
        total_completed_triggered = completed_carts_triggered + completed_no_carts_triggered
        percentage_completed_carts_triggered = Utility.safe_divide(completed_carts_triggered * 100, total_triggered)
        percentage_completed_no_carts_triggered = Utility.safe_divide(completed_no_carts_triggered * 100, total_triggered)
        percentage_total_completed_triggered = Utility.safe_divide(total_completed_triggered * 100, total_triggered)

        # 
        completed_ok_receipts = completed_payments_info.closed_as_ok
        completed_ko_receipts = completed_payments_info.closed_as_ko
        total_completed_receipts = completed_ok_receipts + completed_ko_receipts
        percentage_completed_ok_receipts = Utility.safe_divide(completed_ok_receipts * 100, total_completed_receipts)
        percentage_completed_ko_receipts = Utility.safe_divide(completed_ko_receipts * 100, total_completed_receipts)

        #
        refused_ok_receipts = not_completed_payments_info.rejected.count_ok
        refused_ko_receipts = not_completed_payments_info.rejected.count_ko
        total_refused_receipts = refused_ok_receipts + refused_ko_receipts
        percentage_refused_ok_receipts = Utility.safe_divide(refused_ok_receipts * 100, total_refused_receipts)
        percentage_refused_ko_receipts = Utility.safe_divide(refused_ko_receipts * 100, total_refused_receipts)

        #
        rescheduled_ok_receipts = not_completed_payments_info.scheduled.count_ok
        rescheduled_ko_receipts = not_completed_payments_info.scheduled.count_ko
        total_rescheduled_receipts = rescheduled_ok_receipts + rescheduled_ko_receipts
        percentage_rescheduled_ok_receipts = Utility.safe_divide(rescheduled_ok_receipts * 100, total_rescheduled_receipts)
        percentage_rescheduled_ko_receipts = Utility.safe_divide(rescheduled_ko_receipts * 100, total_rescheduled_receipts)

        #
        end_schedule_ok_receipts = not_completed_payments_info.never_sent.count_ok
        end_schedule_ko_receipts = not_completed_payments_info.never_sent.count_ko
        total_end_schedule_receipts = end_schedule_ok_receipts + end_schedule_ko_receipts
        percentage_end_schedule_ok_receipts = Utility.safe_divide(end_schedule_ok_receipts * 100, total_end_schedule_receipts)
        percentage_end_schedule_ko_receipts = Utility.safe_divide(end_schedule_ko_receipts * 100, total_end_schedule_receipts)

        #
        error_ok_receipts = not_completed_payments_info.blocked.count_ok
        error_ko_receipts = not_completed_payments_info.blocked.count_ko
        total_error_receipts = error_ok_receipts + error_ko_receipts
        percentage_error_ok_receipts = Utility.safe_divide(error_ok_receipts * 100, total_error_receipts)
        percentage_error_ko_receipts = Utility.safe_divide(error_ko_receipts * 100, total_error_receipts)

        #
        total_receipts = total_completed_receipts + total_refused_receipts + total_rescheduled_receipts + total_end_schedule_receipts + total_error_receipts
        percentage_total_completed_receipt = Utility.safe_divide(total_completed_receipts * 100, total_receipts)
        percentage_total_refused_receipts = Utility.safe_divide(total_refused_receipts * 100, total_receipts)
        percentage_total_rescheduled_receipts = Utility.safe_divide(total_rescheduled_receipts * 100, total_receipts)
        percentage_total_end_schedule_receipts = Utility.safe_divide(total_end_schedule_receipts * 100, total_receipts)
        percentage_total_error_receipts = Utility.safe_divide(total_error_receipts * 100, total_receipts)

        self.payments = f'''
        \n*:zap: Numero totale di pagamenti innescati:* `{total_triggered}` \n   di cui _nodoInviaRPT_: `{no_carts_triggered}` (`{percentage_no_carts_triggered:.2f}%`) \n   di cui _nodoInviaCarrelloRPT_: `{carts_triggered}` (`{percentage_carts_triggered:.2f}%`)
        \n*:moneybag: Numero totale di pagamenti completati:* `{total_completed_triggered}/{total_triggered}` (`{percentage_total_completed_triggered:.2f}%`) \n   di cui _nodoInviaRPT_: `{completed_no_carts_triggered}` (`{percentage_completed_no_carts_triggered:.2f}%`) \n   di cui _nodoInviaCarrelloRPT_: `{completed_carts_triggered}` (`{percentage_completed_carts_triggered:.2f}%`)
        \n*:envelope::done: Numero totale di ricevute inviate ed accettate dall'ente:* `{total_completed_receipts}/{total_receipts}` (`{percentage_total_completed_receipt:.2f}%`) \n   di cui per esito pagamento _OK_: `{completed_ok_receipts}` (`{percentage_completed_ok_receipts:.2f}%`) \n   di cui per esito pagamento _KO_: `{completed_ko_receipts}` (`{percentage_completed_ko_receipts:.2f}%`)
        \n*:envelope::no_entry_sign: Numero totale di ricevute inviate ed rifiutate dall'ente:* `{total_refused_receipts}/{total_receipts}` (`{percentage_total_refused_receipts:.2f}%`) \n   di cui per esito pagamento _OK_: `{refused_ok_receipts}` (`{percentage_refused_ok_receipts:.2f}%`) \n   di cui per esito pagamento _KO_: `{refused_ko_receipts}` (`{percentage_refused_ko_receipts:.2f}%`)
        \n*:envelope::repeat: Numero totale di ricevute con invio rischedulato:* `{total_rescheduled_receipts}/{total_receipts}` (`{percentage_total_rescheduled_receipts:.2f}%`) \n   di cui per esito pagamento _OK_: `{rescheduled_ok_receipts}` (`{percentage_rescheduled_ok_receipts:.2f}%`) \n   di cui per esito pagamento _KO_: `{rescheduled_ko_receipts}` (`{percentage_rescheduled_ko_receipts:.2f}%`)
        \n*:envelope::hourglass: Numero totale di ricevute non inviate per fine schedulazione:* `{total_end_schedule_receipts}/{total_receipts}` (`{percentage_total_end_schedule_receipts:.2f}%`) \n   di cui per esito pagamento _OK_: `{end_schedule_ok_receipts}` (`{percentage_end_schedule_ok_receipts:.2f}%`) \n   di cui per esito pagamento _KO_: `{end_schedule_ko_receipts}` (`{percentage_end_schedule_ko_receipts:.2f}%`)
        \n*:envelope::fire: Numero totale di ricevute non inviate per errore generico:* `{total_error_receipts}/{total_receipts}` (`{percentage_total_error_receipts:.2f}%`) \n   di cui per esito pagamento _OK_: `{error_ok_receipts}` (`{percentage_error_ok_receipts:.2f}%`) \n   di cui per esito pagamento _KO_: `{error_ko_receipts}` (`{percentage_error_ko_receipts:.2f}%`)
        '''


    def _extract_details_for_completed_payments(self, total_payments_on_wisp, completed_payments_info: CompletedPaymentsReportInfo):

        closed_as_ok = completed_payments_info.closed_as_ok
        closed_as_ko = completed_payments_info.closed_as_ko
        total_closed = closed_as_ok + closed_as_ko
        percentage_closed_on_total = (total_closed * 100) / total_payments_on_wisp
        percentage_closed_as_ok = closed_as_ok * 100 / total_closed
        percentage_closed_as_ko = closed_as_ko * 100 / total_closed

        return f'''\n>- Numero totale di pagamenti conclusi con ricevuta: `{total_closed} ({percentage_closed_on_total:.2f}%)` \n>- Numero di pagamenti conclusi con ricevuta (pagamento in successo): `{closed_as_ok} ({percentage_closed_as_ok:.2f}%)` \n>- Numero di pagamenti conclusi con ricevuta (pagamento in errore): `{closed_as_ko} ({percentage_closed_as_ko:.2f}%)`'''
    

    def _extract_details_for_not_completed_payments(self, total_payments_on_wisp, not_completed_payments_info: NotCompletedPaymentsReportInfo):

        rejected = not_completed_payments_info.rejected.count
        never_sent = not_completed_payments_info.never_sent.count
        scheduled = not_completed_payments_info.scheduled.count
        blocked = not_completed_payments_info.blocked.count

        total_not_closed = rejected + never_sent + scheduled + blocked
        percentage_not_closed_on_total = (total_not_closed * 100) / total_payments_on_wisp

        percentage_rejected = rejected * 100 / total_payments_on_wisp
        percentage_never_sent = never_sent * 100 / total_payments_on_wisp
        percentage_scheduled = scheduled * 100 / total_payments_on_wisp
        percentage_blocked = blocked * 100 / total_payments_on_wisp

        return f'''\n>- Numero totale di pagamenti senza ricevuta: `{total_not_closed} ({percentage_not_closed_on_total:.2f}%)` \n>- Numero di pagamenti senza ricevuta per rifiuto dell'Ente: `{rejected} ({percentage_rejected:.2f}%)` \n>- Numero di pagamenti con invio ricevuta schedulata per retry: `{scheduled} ({percentage_scheduled:.2f}%)` \n>- Numero di pagamenti senza ricevuta per fine tentativi retry: `{never_sent} ({percentage_never_sent:.2f}%)` \n>- Numero di pagamenti conclusi senza ricevuta per errore imprevisto: `{blocked} ({percentage_blocked:.2f}%)`'''
    