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
        if report_info.cis_total > self.cis_total:
            self.cis_total += report_info.cis_total
        if report_info.cis_on_wisp > self.cis_on_wisp:
            self.cis_on_wisp += report_info.cis_on_wisp
    
    def extract_from_report_entity(report_entity):
        report_data = report_entity.get_map()
        creditor_institution_info = report_data["creditor_institution_info"]
        return CIsReportInfo(cis_total=creditor_institution_info["total"], 
                             cis_on_wisp=creditor_institution_info["on_wisp"])


# ============================================================================
   
class TriggerPrimitiveReportInfo:

    def __init__(self, 
                 carts_completed=0, 
                 carts_total=0, 
                 no_carts_completed=0, 
                 no_carts_total=0,
                 not_completed_rpt_timeout=0,
                 not_completed_redirect=0,
                 not_completed_receipt_ko=0,
                 not_completed_paymenttoken_timeout=0,
                 not_completed_ecommerce_timeout=0,
                 not_completed_no_state=0):
        self.carts_completed = carts_completed
        self.carts_total = carts_total
        self.no_carts_completed = no_carts_completed
        self.no_carts_total = no_carts_total
        self.not_completed_rpt_timeout = not_completed_rpt_timeout
        self.not_completed_redirect = not_completed_redirect
        self.not_completed_receipt_ko = not_completed_receipt_ko
        self.not_completed_paymenttoken_timeout = not_completed_paymenttoken_timeout
        self.not_completed_ecommerce_timeout = not_completed_ecommerce_timeout
        self.not_completed_no_state = not_completed_no_state


    def merge(self, report_info):
        self.carts_completed += report_info.carts_completed
        self.carts_total += report_info.carts_total
        self.no_carts_completed += report_info.no_carts_completed
        self.no_carts_total += report_info.no_carts_total
        self.not_completed_rpt_timeout += report_info.not_completed_rpt_timeout
        self.not_completed_redirect += report_info.not_completed_redirect
        self.not_completed_receipt_ko += report_info.not_completed_receipt_ko
        self.not_completed_paymenttoken_timeout += report_info.not_completed_paymenttoken_timeout
        self.not_completed_ecommerce_timeout += report_info.not_completed_ecommerce_timeout
        self.not_completed_no_state += report_info.not_completed_no_state


    def extract_from_report_entity(report_entity):
        report_data = report_entity.get_map()
        payments_info = report_data["payments"]
        trigger_primitive_info = payments_info["trigger_primitives"]
        trigger_primitive_not_completed_info = trigger_primitive_info[Constants.TRIGGER_PRIMITIVE_NOT_COMPLETED_MACROTAG]
        return TriggerPrimitiveReportInfo(carts_total=trigger_primitive_info["total_carts"],
                                          no_carts_total=trigger_primitive_info["total_no_carts"],
                                          carts_completed=trigger_primitive_info["carts_completed"],
                                          no_carts_completed=trigger_primitive_info["no_carts_completed"],
                                          not_completed_rpt_timeout=trigger_primitive_not_completed_info[Constants.TRIGGER_PRIMITIVE_NOT_COMPLETED_RPT_TIMEOUT],
                                          not_completed_redirect=trigger_primitive_not_completed_info[Constants.TRIGGER_PRIMITIVE_NOT_COMPLETED_REDIRECT],
                                          not_completed_receipt_ko=trigger_primitive_not_completed_info[Constants.TRIGGER_PRIMITIVE_NOT_COMPLETED_RECEIPT_KO],
                                          not_completed_paymenttoken_timeout=trigger_primitive_not_completed_info[Constants.TRIGGER_PRIMITIVE_NOT_COMPLETED_PAYMENTTOKEN_TIMEOUT],
                                          not_completed_ecommerce_timeout=trigger_primitive_not_completed_info[Constants.TRIGGER_PRIMITIVE_NOT_COMPLETED_ECOMMERCE_TIMEOUT],
                                          not_completed_no_state=trigger_primitive_not_completed_info[Constants.TRIGGER_PRIMITIVE_NOT_COMPLETED_NO_STATE])

# ============================================================================

class CompletedPaymentsReportInfo:

    def __init__(self, 
                 closed_as_ok=0, 
                 closed_as_ko=0):
        self.closed_as_ok = closed_as_ok
        self.closed_as_ko = closed_as_ko


    def merge(self, report_info):
        self.closed_as_ok += report_info.closed_as_ok
        self.closed_as_ko += report_info.closed_as_ko


    def extract_from_report_entity(report_entity):
        report_data = report_entity.get_map()
        payments_info = report_data["receipts"]
        completed_payments_info = payments_info[Constants.COMPLETED_MACROTAG]
        return CompletedPaymentsReportInfo(closed_as_ok=completed_payments_info[Constants.COMPLETED_OK_RECEIPT_TOTAL],
                                           closed_as_ko=completed_payments_info[Constants.COMPLETED_KO_RECEIPT_TOTAL])


# ============================================================================

class NotCompletedPaymentsReportInfo:

    def __init__(self,
                 rejected: ReceiptDetailStatistics = ReceiptDetailStatistics(),
                 not_sent_end_retry: ReceiptDetailStatistics = ReceiptDetailStatistics(),
                 scheduled: ReceiptDetailStatistics = ReceiptDetailStatistics(),
                 ongoing: ReceiptDetailStatistics = ReceiptDetailStatistics(),
                 never_sent: ReceiptDetailStatistics = ReceiptDetailStatistics()):
        self.rejected = rejected
        self.not_sent_end_retry = not_sent_end_retry
        self.scheduled = scheduled
        self.ongoing = ongoing
        self.never_sent = never_sent


    def merge(self, report_info):
        self.rejected.merge(report_info.rejected)
        self.not_sent_end_retry.merge(report_info.not_sent_end_retry)
        self.scheduled.merge(report_info.scheduled)
        self.ongoing.merge(report_info.ongoing)
        self.never_sent.merge(report_info.never_sent)


    def extract_from_report_entity(report_entity):
        report_data = report_entity.get_map()
        receipts_info = report_data["receipts"]
        not_completed_receipts_info = receipts_info[Constants.NOT_COMPLETED_MACROTAG]
        rejected_receipts_info = not_completed_receipts_info[Constants.NOT_COMPLETED_REJECTED]
        not_sent_end_retry_receipts_info = not_completed_receipts_info[Constants.NOT_COMPLETED_NOT_SENT_END_RETRY]
        scheduled_receipts_info = not_completed_receipts_info[Constants.NOT_COMPLETED_SCHEDULED]
        ongoing_receipts_info = not_completed_receipts_info[Constants.NOT_COMPLETED_ONGOING]
        never_sent_receipts_info = not_completed_receipts_info[Constants.NOT_COMPLETED_NEVER_SENT]
        return NotCompletedPaymentsReportInfo(rejected=ReceiptDetailStatistics(receipt_ok_count=rejected_receipts_info[Constants.RECEIPT_OK_COUNT],
                                                                               receipt_ko_count=rejected_receipts_info[Constants.RECEIPT_KO_COUNT]),
                                              not_sent_end_retry=ReceiptDetailStatistics(receipt_ok_count=not_sent_end_retry_receipts_info[Constants.RECEIPT_OK_COUNT],
                                                                                 receipt_ko_count=not_sent_end_retry_receipts_info[Constants.RECEIPT_KO_COUNT]),
                                              scheduled=ReceiptDetailStatistics(receipt_ok_count=scheduled_receipts_info[Constants.RECEIPT_OK_COUNT],
                                                                                receipt_ko_count=scheduled_receipts_info[Constants.RECEIPT_KO_COUNT]),
                                              ongoing=ReceiptDetailStatistics(receipt_ok_count=ongoing_receipts_info[Constants.RECEIPT_OK_COUNT],
                                                                              receipt_ko_count=scheduled_receipts_info[Constants.RECEIPT_KO_COUNT]),
                                              never_sent=ReceiptDetailStatistics(receipt_ok_count=never_sent_receipts_info[Constants.RECEIPT_OK_COUNT],
                                                                                 receipt_ko_count=never_sent_receipts_info[Constants.RECEIPT_KO_COUNT]))



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
        \n>:information_source: _*Nota:*_ \n> _Di seguito si fa riferimento *solo* alle primitive *nodoInviaRPT* e *nodoInviaCarrelloRPT*._ \n>\n> _Per ricevuta si fa riferimento alla *paaInviaRT* inviata all'ente. Possono essere generate più ricevute per uno stesso *tentativo di pagamento* innescato, il quale è dichiarato concluso solo se tutte le ricevute associate allo stesso innesco sono OK. Se la circostanza precedente non è verificata, si approfondisce la casistica di errore sulle ricevute includendola in una statistica dedicata._
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
                
        # Divided by trigger primitives not completed on D-WISP
        triggered_error_rpt_timeout = trigger_primitive_info.not_completed_rpt_timeout
        triggered_error_redirect = trigger_primitive_info.not_completed_redirect
        triggered_error_receipt_ko = trigger_primitive_info.not_completed_receipt_ko
        triggered_error_ecommerce_timeout = trigger_primitive_info.not_completed_ecommerce_timeout
        triggered_error_paymenttoken_timeout = trigger_primitive_info.not_completed_paymenttoken_timeout
        triggered_error_nostate = trigger_primitive_info.not_completed_no_state        
        total_non_completed_triggered = triggered_error_rpt_timeout + triggered_error_redirect + triggered_error_receipt_ko + triggered_error_ecommerce_timeout + triggered_error_paymenttoken_timeout + triggered_error_nostate
        percentage_error_rpt_timeout = Utility.safe_divide(triggered_error_rpt_timeout * 100, total_triggered)
        percentage_error_redirect = Utility.safe_divide(triggered_error_redirect * 100, total_triggered)
        percentage_error_receipt_ko = Utility.safe_divide(triggered_error_receipt_ko * 100, total_triggered)
        percentage_error_ecommerce_timeout = Utility.safe_divide(triggered_error_ecommerce_timeout * 100, total_triggered)
        percentage_error_paymenttoken_timeout = Utility.safe_divide(triggered_error_paymenttoken_timeout * 100, total_triggered)
        percentage_error_nostate = Utility.safe_divide(triggered_error_nostate * 100, total_triggered)
        percentage_total_non_completed_triggered = Utility.safe_divide(total_non_completed_triggered * 100, total_triggered)

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
        refused_ok_receipts = not_completed_payments_info.rejected.receipt_ok_count
        refused_ko_receipts = not_completed_payments_info.rejected.receipt_ko_count
        total_refused_receipts = refused_ok_receipts + refused_ko_receipts
        percentage_refused_ok_receipts = Utility.safe_divide(refused_ok_receipts * 100, total_refused_receipts)
        percentage_refused_ko_receipts = Utility.safe_divide(refused_ko_receipts * 100, total_refused_receipts)

        #
        rescheduled_ok_receipts = not_completed_payments_info.scheduled.receipt_ok_count
        rescheduled_ko_receipts = not_completed_payments_info.scheduled.receipt_ko_count
        total_rescheduled_receipts = rescheduled_ok_receipts + rescheduled_ko_receipts
        percentage_rescheduled_ok_receipts = Utility.safe_divide(rescheduled_ok_receipts * 100, total_rescheduled_receipts)
        percentage_rescheduled_ko_receipts = Utility.safe_divide(rescheduled_ko_receipts * 100, total_rescheduled_receipts)

        #
        ongoing_ok_receipts = not_completed_payments_info.ongoing.receipt_ok_count
        ongoing_ko_receipts = not_completed_payments_info.ongoing.receipt_ko_count
        total_ongoing_receipts = ongoing_ok_receipts + ongoing_ko_receipts
        percentage_ongoing_ok_receipts = Utility.safe_divide(ongoing_ok_receipts * 100, total_ongoing_receipts)
        percentage_ongoing_ko_receipts = Utility.safe_divide(ongoing_ko_receipts * 100, total_ongoing_receipts)

        #
        end_schedule_ok_receipts = not_completed_payments_info.not_sent_end_retry.receipt_ok_count
        end_schedule_ko_receipts = not_completed_payments_info.not_sent_end_retry.receipt_ko_count
        total_end_schedule_receipts = end_schedule_ok_receipts + end_schedule_ko_receipts
        percentage_end_schedule_ok_receipts = Utility.safe_divide(end_schedule_ok_receipts * 100, total_end_schedule_receipts)
        percentage_end_schedule_ko_receipts = Utility.safe_divide(end_schedule_ko_receipts * 100, total_end_schedule_receipts)

        #
        error_ok_receipts = not_completed_payments_info.never_sent.receipt_ok_count
        error_ko_receipts = not_completed_payments_info.never_sent.receipt_ko_count
        total_error_receipts = error_ok_receipts + error_ko_receipts
        percentage_error_ok_receipts = Utility.safe_divide(error_ok_receipts * 100, total_error_receipts)
        percentage_error_ko_receipts = Utility.safe_divide(error_ko_receipts * 100, total_error_receipts)

        #
        total_receipts = total_completed_receipts + total_refused_receipts + total_rescheduled_receipts + total_ongoing_receipts + total_end_schedule_receipts + total_error_receipts
        percentage_total_completed_receipt = Utility.safe_divide(total_completed_receipts * 100, total_receipts)
        percentage_total_refused_receipts = Utility.safe_divide(total_refused_receipts * 100, total_receipts)
        percentage_total_rescheduled_receipts = Utility.safe_divide(total_rescheduled_receipts * 100, total_receipts)
        percentage_total_ongoing_receipts = Utility.safe_divide(total_ongoing_receipts * 100, total_receipts)
        percentage_total_end_schedule_receipts = Utility.safe_divide(total_end_schedule_receipts * 100, total_receipts)
        percentage_total_error_receipts = Utility.safe_divide(total_error_receipts * 100, total_receipts)

        self.payments = f'''
        \n*:zap: Numero totale di pagamenti innescati:* `{total_triggered}` \n   di cui _nodoInviaRPT_: `{no_carts_triggered}` (`{percentage_no_carts_triggered:.2f}%`) \n   di cui _nodoInviaCarrelloRPT_: `{carts_triggered}` (`{percentage_carts_triggered:.2f}%`)
        \n*:moneybag::done: Numero totale di pagamenti completati:* `{total_completed_triggered}/{total_triggered}` (`{percentage_total_completed_triggered:.2f}%`) \n   di cui _nodoInviaRPT_: `{completed_no_carts_triggered}` (`{percentage_completed_no_carts_triggered:.2f}%`) \n   di cui _nodoInviaCarrelloRPT_: `{completed_carts_triggered}` (`{percentage_completed_carts_triggered:.2f}%`)
        \n*:moneybag::no_entry_sign: Numero totale di pagamenti non completati:* `{total_non_completed_triggered}/{total_triggered}` (`{percentage_total_non_completed_triggered:.2f}%`) \n   di cui _KO nel flusso del Nodo dei Pagamenti_: `{triggered_error_receipt_ko}/{total_non_completed_triggered}` (`{percentage_error_receipt_ko:.2f}%`) \n   di cui _timeout per mancata redirect da primitiva di innesco_: `{triggered_error_rpt_timeout}/{total_non_completed_triggered}` (`{percentage_error_rpt_timeout:.2f}%`) \n   di cui _problema di conversione in NMU_: `{triggered_error_redirect}/{total_non_completed_triggered}` (`{percentage_error_redirect:.2f}%`) \n   di cui _timeout per mancata chiusura esito pagamento_: `{triggered_error_paymenttoken_timeout}/{total_non_completed_triggered}` (`{percentage_error_paymenttoken_timeout:.2f}%`) \n   di cui _timeout per abbandono o attesa da Checkout_: `{triggered_error_ecommerce_timeout}/{total_non_completed_triggered}` (`{percentage_error_ecommerce_timeout:.2f}%`) \n   di cui _per errore imprevisto (nessuna ricevuta inviata)_: `{triggered_error_nostate}/{total_non_completed_triggered}` (`{percentage_error_nostate:.2f}%`)        
        \n*:envelope::done: Numero totale di ricevute inviate ed accettate dall'ente:* `{total_completed_receipts}/{total_receipts}` (`{percentage_total_completed_receipt:.2f}%`) \n   di cui per esito pagamento _OK_: `{completed_ok_receipts}` (`{percentage_completed_ok_receipts:.2f}%`) \n   di cui per esito pagamento _KO_: `{completed_ko_receipts}` (`{percentage_completed_ko_receipts:.2f}%`)
        \n*:envelope::no_entry_sign: Numero totale di ricevute inviate e rifiutate dall'ente:* `{total_refused_receipts}/{total_receipts}` (`{percentage_total_refused_receipts:.2f}%`) \n   di cui per esito pagamento _OK_: `{refused_ok_receipts}` (`{percentage_refused_ok_receipts:.2f}%`) \n   di cui per esito pagamento _KO_: `{refused_ko_receipts}` (`{percentage_refused_ko_receipts:.2f}%`)
        \n*:envelope::repeat: Numero totale di ricevute con invio schedulato:* `{total_rescheduled_receipts}/{total_receipts}` (`{percentage_total_rescheduled_receipts:.2f}%`) \n   di cui per esito pagamento _OK_: `{rescheduled_ok_receipts}` (`{percentage_rescheduled_ok_receipts:.2f}%`) \n   di cui per esito pagamento _KO_: `{rescheduled_ko_receipts}` (`{percentage_rescheduled_ko_receipts:.2f}%`)
        \n*:envelope::hourglass: Numero totale di ricevute non inviate per fine schedulazione:* `{total_end_schedule_receipts}/{total_receipts}` (`{percentage_total_end_schedule_receipts:.2f}%`) \n   di cui per esito pagamento _OK_: `{end_schedule_ok_receipts}` (`{percentage_end_schedule_ok_receipts:.2f}%`) \n   di cui per esito pagamento _KO_: `{end_schedule_ko_receipts}` (`{percentage_end_schedule_ko_receipts:.2f}%`)
        \n*:envelope::dollar: Numero totale di ricevute escluse dal conteggio (pagamento in corso):* `{total_ongoing_receipts}/{total_receipts}` (`{percentage_total_ongoing_receipts:.2f}%`) \n   di cui per esito pagamento _OK_: `{ongoing_ok_receipts}` (`{percentage_ongoing_ok_receipts:.2f}%`) \n   di cui per esito pagamento _KO_: `{ongoing_ko_receipts}` (`{percentage_ongoing_ko_receipts:.2f}%`)
        \n*:envelope::fire: Numero totale di ricevute non inviate per errore generico:* `{total_error_receipts}/{total_receipts}` (`{percentage_total_error_receipts:.2f}%`) \n   di cui per esito pagamento _OK_: `{error_ok_receipts}` (`{percentage_error_ok_receipts:.2f}%`) \n   di cui per esito pagamento _KO_: `{error_ko_receipts}` (`{percentage_error_ko_receipts:.2f}%`)
        '''