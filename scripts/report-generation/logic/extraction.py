import logging

from logic.clients import WispDismantlingDatabase, APIConfigCacheClient
from datastructs.configuration import Parameters
from datastructs.report import CIsReportInfo, CompletedPaymentsReportInfo, NotCompletedPaymentsReportInfo, Report, ReportNotificationDetail, ReportNumericData, TriggerPrimitiveReportInfo
from datastructs.entities import ReportEntity
from datastructs.dtos import CompletedReceiptStatistics, NotCompletedReceiptStatistics, PrimitiveTriggerStatistics, ReceiptDetailStatistics
from utility.constants import Constants
from utility.utility import Utility

class Extractor:

    def __init__(self, date, parameters: Parameters, type=None):
        self.date = date
        self.type = type
        self.parameters = parameters


    def _extract_number_of_creditor_institution_on_wisp(self, db: WispDismantlingDatabase, apiconfig_client: APIConfigCacheClient):
        
        creditor_institutions_on_wisp = db.get_active_creditor_institution()
        ci_station_associations = apiconfig_client.get_creditor_institution_station()
        if ci_station_associations is not None:
            ci_station_associations = ci_station_associations["creditorInstitutionStations"]
        else:
            ci_station_associations = {}
        
        if len(creditor_institutions_on_wisp) == 1 and list(creditor_institutions_on_wisp)[0] == Constants.WILDCARD_CHARACTER:
            for _, association in ci_station_associations.items():
                creditor_institution = association['creditor_institution_code']
                seg_code = association['segregation_code']
                if seg_code == Constants.SEGREGATION_CODE_FOR_WISP:
                    creditor_institutions_on_wisp.add(creditor_institution)

        all_creditor_institutions = set(element["creditor_institution_code"] for element in ci_station_associations.values())
        return (len(creditor_institutions_on_wisp), len(all_creditor_institutions))


    def _extract_trigger_request_from_wisp(self, db: WispDismantlingDatabase):

        trigger_primitives = db.get_trigger_primitive_by_date(self.date)
        statistics = PrimitiveTriggerStatistics()

        for trigger_primitive in trigger_primitives:
            if trigger_primitive.primitive == Constants.NODO_INVIA_CARRELLO_RPT:
                statistics.carts.add(trigger_primitive.session_id)
            if trigger_primitive.primitive == Constants.NODO_INVIA_RPT:
                statistics.no_carts.add(trigger_primitive.session_id)

        return statistics
    

    def _extract_payment_status_tracking_by_session_id(self, grouped_trigger_primitives: PrimitiveTriggerStatistics, db: WispDismantlingDatabase):
        
        # merge all primitives' session IDs in a single list
        all_session_ids = []
        carts_session_ids = grouped_trigger_primitives.carts.session_ids
        no_carts_session_ids = grouped_trigger_primitives.no_carts.session_ids
        all_session_ids.extend(carts_session_ids)
        all_session_ids.extend(no_carts_session_ids)
        
        completed_payments = CompletedReceiptStatistics()
        not_completed_payments = NotCompletedReceiptStatistics()

        completed_carts = set()
        completed_no_carts = set()

        for session_id in all_session_ids:

            re_events = db.get_payment_status_by_re_events(session_id)
            
            number_of_events = len(re_events)
            rts_sent = 0
            rts_not_sent = 0

            for re_event in re_events:
                status = re_event.status
                if status == 'RT_SEND_SUCCESS':
                    rts_sent += 1
                if status == 'RT_SEND_FAILURE':
                    rts_not_sent += 1

            if number_of_events == 0 or rts_not_sent == number_of_events or rts_sent != number_of_events:
                
                receipts = db.get_receipts_by_session_id(session_id)
                for receipt in receipts:
                    
                    receipt_status = receipt.status
                    receipt_id = receipt.id

                    if receipt_status == 'SENT':
                        completed_payments.ok_receipt_sent_by_retry += 1
                        if receipt.type == 'OK':
                            completed_payments.add_as_ok()
                        else:
                            completed_payments.add_as_ko()
                        
                    elif receipt_status == 'SENT_REJECTED_BY_EC':
                        if receipt.type == 'OK':
                            not_completed_payments.rejected.add_as_ok(receipt_id)
                        else:
                            not_completed_payments.rejected.add_as_ko(receipt_id)

                    elif receipt_status == 'NOT_SENT':
                        if receipt.type == 'OK':
                            not_completed_payments.never_sent.add_as_ok(receipt_id)
                        else:
                            not_completed_payments.never_sent.add_as_ko(receipt_id)

                    elif receipt_status == 'SCHEDULED' or receipt_status == 'SENDING':
                        if receipt.type == 'OK':
                            not_completed_payments.sending_or_scheduled.add_as_ok(receipt_id)
                        else:
                            not_completed_payments.sending_or_scheduled.add_as_ko(receipt_id)

                    else:
                        if receipt.type == 'OK':
                            not_completed_payments.blocked.add_as_ok(receipt_id)
                        else:
                            not_completed_payments.blocked.add_as_ko(receipt_id)

            else:
                re_event_to_check = re_events[0]
                if re_event_to_check.business_process == 'receipt-ok':
                    completed_payments.add_as_ok()
                    if session_id in carts_session_ids: 
                        completed_carts.add(session_id)
                    else:
                        completed_no_carts.add(session_id)
                else:
                    completed_payments.add_as_ko()

        return (completed_payments, not_completed_payments, len(completed_carts), len(completed_no_carts))

            


    def generate_report_data(self, db_client: WispDismantlingDatabase, apiconfig_client = APIConfigCacheClient):
        
        report = Report(self.date, self.parameters)
        numeric_data = None
        
        # Extract data for daily report
        if self.type == Constants.DAILY:
            numeric_data = self._generate_numeric_data_for_daily_report(db_client, apiconfig_client)            

        # Extract data for weekly report
        elif self.type == Constants.WEEKLY:
            numeric_data = self._generate_numeric_data_for_weekly_report(db_client)

        # Extract data for monthly report
        elif self.type == Constants.MONTHLY:
            numeric_data = self._generate_numeric_data_for_monthly_report(db_client)

        # Invalid report type, cannot generate anything
        else:
            logging.warning(f"\t[WARN ][Extractor      ] No valid report type set, no data is generated.")

        # last step: populate data and extract report statistics
        report.populate_with_data(numeric_data)  

        # finally, persist report in DB
        db_client.store_report(report, self.type)

        return report
    

    # Generate numeric values to be set on daily report 
    def _generate_numeric_data_for_daily_report(self, db_client: WispDismantlingDatabase, apiconfig_client = APIConfigCacheClient):
        
        numeric_data = ReportNumericData()

        # Retrieve data about the total number of payments in Nodo for the passed date
        logging.info(f"\t[INFO ][Extractor      ] Executing event count from Data Explorer for NdP...")
        numeric_data.total_payments_on_ndp = db_client.read_ndp_payments_by_date(self.date)
        logging.info(f"\t[INFO ][Extractor      ] Executed event count from Data Explorer for NdP! Retrieved count: [{numeric_data.total_payments_on_ndp}]")

        # Generate statistics about creditor institutions
        (cis_on_wisp, total_cis) = self._extract_number_of_creditor_institution_on_wisp(db=db_client, apiconfig_client=apiconfig_client)    
        numeric_data.creditor_institutions = CIsReportInfo(total_cis, cis_on_wisp)    

        # Generate statistics about triggered primitives
        logging.info(f"\t[INFO ][Extractor      ] Executing read for trigger primitives from D-WISP...")
        grouped_trigger_requests = self._extract_trigger_request_from_wisp(db=db_client)
        logging.info(f"\t[INFO ][Extractor      ] Retrieved {len(grouped_trigger_requests.carts) + len(grouped_trigger_requests.carts)} primitives! Executing read for statistics about payments and receipts...")
        (completed_payments, not_completed_payments, completed_carts, completed_no_carts) = self._extract_payment_status_tracking_by_session_id(grouped_trigger_requests, db_client)
        logging.info(f"\t[INFO ][Extractor      ] Retrieved statistics about payments and receipts! Finalizing report persistence...")

        # Generate statistics about trigger primitives
        numeric_data.trigger_primitives = TriggerPrimitiveReportInfo(carts_completed=completed_carts,
                                                                     carts_total=grouped_trigger_requests.carts.count, 
                                                                     no_carts_completed=completed_no_carts,
                                                                     no_carts_total=grouped_trigger_requests.no_carts.count)
            
        # Generate statistics about completed payments
        numeric_data.completed_payments = CompletedPaymentsReportInfo(closed_as_ok=completed_payments.sent_ok_receipts,
                                                                      closed_as_ko=completed_payments.sent_ko_receipts,
                                                                      ok_receipt_sent_by_retry=completed_payments.ok_receipt_sent_by_retry,
                                                                      ko_receipt_sent_by_retry=completed_payments.ko_receipt_sent_by_retry)
            
        # Generate statistics about not completed payments
        numeric_data.not_completed_payments = NotCompletedPaymentsReportInfo(never_sent=not_completed_payments.never_sent,
                                                                             rejected=not_completed_payments.rejected,
                                                                             scheduled=not_completed_payments.sending_or_scheduled,
                                                                             blocked=not_completed_payments.blocked)
        return numeric_data
    

    # Generate numeric values to be set on weekly report 
    def _generate_numeric_data_for_weekly_report(self, db_client: WispDismantlingDatabase):
        week_days = Utility.get_week_before_date(self.date)
        logging.info(f"\t[INFO ][Extractor      ] Generating merged weekly report for dates {week_days}.")
        return self._generate_numeric_data_for_multiple_days_report(db_client, week_days)
        

    def _generate_numeric_data_for_monthly_report(self, db_client: WispDismantlingDatabase):
        month_days = Utility.get_month_before_date(self.date)
        logging.info(f"\t[INFO ][Extractor      ] Generating merged monthly report for dates {month_days}.")
        return self._generate_numeric_data_for_multiple_days_report(db_client, month_days)


    def _generate_numeric_data_for_multiple_days_report(self, db_client: WispDismantlingDatabase, days):
        numeric_data = ReportNumericData()
        for day in days:        
            report_entity = db_client.retrieve_report(day, Constants.DAILY)
            if report_entity is not None:
                report_data = report_entity.get_map()
                numeric_data.total_payments_on_ndp += report_data["payments"]["total_on_ndp"]
                numeric_data.creditor_institutions.merge(CIsReportInfo.extract_from_report_entity(report_entity))
                numeric_data.trigger_primitives.merge(TriggerPrimitiveReportInfo.extract_from_report_entity(report_entity))
                numeric_data.completed_payments.merge(CompletedPaymentsReportInfo.extract_from_report_entity(report_entity))
                numeric_data.not_completed_payments.merge(NotCompletedPaymentsReportInfo.extract_from_report_entity(report_entity))                
        return numeric_data


    def generate_report_notification_detail(self, report_entity: ReportEntity) -> ReportNotificationDetail:
        
        report_data = report_entity.get_map()
        date = report_data.get("date")

        # extract numerical details from stored report
        total_payments_on_ndp = report_data["payments"]["total_on_ndp"]
        cis_report_info = CIsReportInfo.extract_from_report_entity(report_entity)
        trigger_primitive_report_info = TriggerPrimitiveReportInfo.extract_from_report_entity(report_entity)
        completed_payments_report_info = CompletedPaymentsReportInfo.extract_from_report_entity(report_entity)
        not_completed_payments_report_info = NotCompletedPaymentsReportInfo.extract_from_report_entity(report_entity)
        
        # generate report notification detail
        report_notification_detail = ReportNotificationDetail(date)
        report_notification_detail.extract_details_for_general_info(creditor_institution_info=cis_report_info, 
                                                                    trigger_primitive_info=trigger_primitive_report_info,
                                                                    payments_on_ndp=total_payments_on_ndp)
        report_notification_detail.extract_detail_for_payments(trigger_primitive_report_info,
                                                               completed_payments_report_info,
                                                               not_completed_payments_report_info)
        
        return report_notification_detail

    