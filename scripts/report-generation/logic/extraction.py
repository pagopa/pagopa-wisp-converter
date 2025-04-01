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
        rptcarts_session_ids = grouped_trigger_primitives.carts.session_ids
        singlerpt_session_ids = grouped_trigger_primitives.no_carts.session_ids
        all_session_ids.extend(rptcarts_session_ids)
        all_session_ids.extend(singlerpt_session_ids)
        
        completed_receipts = CompletedReceiptStatistics()
        not_completed_receipts = NotCompletedReceiptStatistics()
        not_completed_triggers = []

        completed_carts = set()
        completed_no_carts = set()
        number_of_primitives = len(all_session_ids)
        already_analyzed_count = 0

        sessionid_in_errors = set()
        for session_id in all_session_ids:

            # Showing "already analyzed" count for better tracking process
            already_analyzed_count += 1
            if already_analyzed_count % 100 == 0:
                percentage_analyzed = Utility.safe_divide(already_analyzed_count * 100, number_of_primitives)
                logging.info(f"\t[INFO ][Extractor      ] At [{Utility.get_now_datetime()}] report details were generated on [{already_analyzed_count}/{number_of_primitives}] triggered primitives ({percentage_analyzed:.03f}%)...")

            receipts = db.get_receipts_by_session_id(session_id)

            # Fallback if no RT is written in receipts-rt
            if len(receipts) == 0:
                sessionid_in_errors.add(session_id)

            for receipt in receipts:
                    
                receipt_status = receipt.status
                receipt_id = receipt.id

                # The receipt was correctly sent (either by direct send or by retry), add it as completed
                if receipt_status == 'SENT':
                    if receipt.type == 'OK':
                        completed_receipts.add_as_ok()
                        # Include sessionId for define the counter of completed RPT carts/single RPT
                        if session_id in singlerpt_session_ids:
                            completed_no_carts.add(session_id)
                        else:
                            completed_carts.add(session_id)
                    else:
                        sessionid_in_errors.add(session_id)
                        completed_receipts.add_as_ko()
                        
                # The receipt was refused by creditor institution
                elif receipt_status == 'SENT_REJECTED_BY_EC':
                    if receipt.type == 'OK':
                        not_completed_receipts.rejected.add_as_ok(receipt_id)
                        # Include sessionId for define the counter of completed RPT carts/single RPT
                        if session_id in singlerpt_session_ids:
                            completed_no_carts.add(session_id)
                        else:
                            completed_carts.add(session_id)
                    else:
                        sessionid_in_errors.add(session_id)
                        not_completed_receipts.rejected.add_as_ko(receipt_id)
                        
                # The receipt was not sent because the number of tries occurred are greater than max limit 
                elif receipt_status == 'NOT_SENT':
                    if receipt.type == 'OK':
                        not_completed_receipts.not_sent_end_retry.add_as_ok(receipt_id)
                        # Include sessionId for define the counter of completed RPT carts/single RPT
                        if session_id in singlerpt_session_ids:
                            completed_no_carts.add(session_id)
                        else:
                            completed_carts.add(session_id)
                    else:
                        sessionid_in_errors.add(session_id)
                        not_completed_receipts.not_sent_end_retry.add_as_ko(receipt_id)

                # The receipt was not sent but the operation is scheduled and it could result as send in future
                elif receipt_status == 'SCHEDULED' or receipt_status == 'SENDING':
                    if receipt.type == 'OK':
                        not_completed_receipts.sending_or_scheduled.add_as_ok(receipt_id)
                        # Include sessionId for define the counter of completed RPT carts/single RPT
                        if session_id in singlerpt_session_ids:
                            completed_no_carts.add(session_id)
                        else:
                            completed_carts.add(session_id)
                    else:
                        sessionid_in_errors.add(session_id)
                        not_completed_receipts.sending_or_scheduled.add_as_ko(receipt_id)

                # The receipt was not sent but the operation is ongoing
                elif receipt_status == 'REDIRECT' or receipt_status == 'PAYING':
                    if receipt.type == 'OK':
                        not_completed_receipts.ongoing.add_as_ok(receipt_id)
                    else:
                        sessionid_in_errors.add(session_id)
                        not_completed_receipts.ongoing.add_as_ko(receipt_id)

                # The receipt was not sent and it is in an not-consistent state
                else:
                    if receipt.type == 'OK':
                        not_completed_receipts.never_sent.add_as_ok(receipt_id)
                    else:
                        sessionid_in_errors.add(session_id)
                        not_completed_triggers.append(Constants.TRIGGER_PRIMITIVE_NOT_COMPLETED_NO_STATE)
                        not_completed_receipts.never_sent.add_as_ko(receipt_id)
            
        # Cataloguing errors by triggered business process
        logging.info(f"\t[INFO ][Extractor      ] Retrieving the cause on KO receipt for [{len(set(sessionid_in_errors))}] sessions...")
        for session_id in sessionid_in_errors:
            events = db.get_rt_trigger_by_re_events(session_id)
            event_set = set((event.session_id, event.business_process) for event in events)
            for event in event_set:
                business_process = event[1].replace('-', '_')
                not_completed_triggers.append(business_process)
        
        logging.info(f"\t[INFO ][Extractor      ] At [{Utility.get_now_datetime()}] report details were generated all on [{number_of_primitives}] triggered primitives!")
        not_completed_triggers_map = {
            Constants.TRIGGER_PRIMITIVE_NOT_COMPLETED_RPT_TIMEOUT: 0,
            Constants.TRIGGER_PRIMITIVE_NOT_COMPLETED_REDIRECT: 0,
            Constants.TRIGGER_PRIMITIVE_NOT_COMPLETED_RECEIPT_KO: 0,
            Constants.TRIGGER_PRIMITIVE_NOT_COMPLETED_ECOMMERCE_TIMEOUT: 0,
            Constants.TRIGGER_PRIMITIVE_NOT_COMPLETED_PAYMENTTOKEN_TIMEOUT: 0,
            Constants.TRIGGER_PRIMITIVE_NOT_COMPLETED_NO_STATE: 0,
        }
        for business_process in not_completed_triggers:
            if business_process in not_completed_triggers_map:
                not_completed_triggers_map[business_process] += 1

        return (completed_receipts, not_completed_receipts, not_completed_triggers_map, len(completed_carts), len(completed_no_carts))

       
    """
    def _extract_payment_status_tracking_by_session_id_OLD(self, grouped_trigger_primitives: PrimitiveTriggerStatistics, db: WispDismantlingDatabase):
        
        # merge all primitives' session IDs in a single list
        all_session_ids = []
        all_session_ids.extend(grouped_trigger_primitives.carts.session_ids)
        all_session_ids.extend(grouped_trigger_primitives.no_carts.session_ids)
        
        completed_payments = CompletedReceiptStatistics()
        not_completed_payments = NotCompletedReceiptStatistics()
        not_completed_triggers = []

        completed_carts = set()
        completed_no_carts = set()
        already_analyzed_count = 0

        for session_id in all_session_ids:

            # Showing "already analyzed" count for better tracking process
            already_analyzed_count += 1
            if already_analyzed_count % 100 == 0:
                number_of_primitives = len(all_session_ids)
                percentage_analyzed = Utility.safe_divide(already_analyzed_count * 100, number_of_primitives)
                logging.info(f"\t[INFO ][Extractor      ] At [{Utility.get_now_datetime()}] report details were generated on [{already_analyzed_count}/{number_of_primitives}] triggered primitives ({percentage_analyzed:.03f}%)...")


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

            #
            if number_of_events != 0:
                business_processes = set(event.business_process.replace('-', '_') for event in re_events)
                if len(business_processes) == 1:

                    business_process = business_processes.pop()
                    
                    #
                    if business_process == 'receipt_ok':
                        if session_id in carts_session_ids:
                            completed_carts.add(session_id)
                        else:
                            completed_no_carts.add(session_id)
                        
                        if rts_sent == number_of_events:
                            completed_payments.add_as_ok()
                        else:
                            completed_payments.add_as_ko()
                            #not_completed_triggers.append(Constants.TRIGGER_PRIMITIVE_NOT_COMPLETED_NO_STATE) 

                    #                            
                    else:
                        not_completed_triggers.append(business_process)    

                else:
                    not_completed_triggers.append(Constants.TRIGGER_PRIMITIVE_NOT_COMPLETED_NO_STATE)
            
            #
            elif number_of_events == 0 or rts_not_sent == number_of_events or rts_sent != number_of_events:
                
                
                receipts = db.get_receipts_by_session_id(session_id)
                for receipt in receipts:
                    
                    receipt_status = receipt.status
                    receipt_id = receipt.id

                    if receipt_status == 'SENT':
                        if receipt.type == 'OK':
                            completed_payments.add_as_ok()
                            completed_payments.with_ok_receipts_only_sent_after_retry += 1
                        else:
                            completed_payments.add_as_ko()
                            completed_payments.with_ko_receipts_only_sent_after_retry += 1
                        
                    elif receipt_status == 'SENT_REJECTED_BY_EC':
                        if receipt.type == 'OK':
                            not_completed_payments.rejected.add_as_ok(receipt_id)
                        else:
                            not_completed_payments.rejected.add_as_ko(receipt_id)

                    elif receipt_status == 'NOT_SENT':
                        if receipt.type == 'OK':
                            not_completed_payments.not_sent_end_retry.add_as_ok(receipt_id)
                        else:
                            not_completed_payments.not_sent_end_retry.add_as_ko(receipt_id)

                    elif receipt_status == 'SCHEDULED' or receipt_status == 'SENDING':
                        if receipt.type == 'OK':
                            not_completed_payments.sending_or_scheduled.add_as_ok(receipt_id)
                        else:
                            not_completed_payments.sending_or_scheduled.add_as_ko(receipt_id)

                    else:
                        if receipt.type == 'OK':
                            not_completed_payments.never_sent.add_as_ok(receipt_id)
                        else:
                            not_completed_payments.never_sent.add_as_ko(receipt_id)
                
                if number_of_events == 0:
                    not_completed_triggers.append(Constants.TRIGGER_PRIMITIVE_NOT_COMPLETED_NO_STATE)

        logging.info(f"\t[INFO ][Extractor      ] At [{Utility.get_now_datetime()}] report details were generated all on [{number_of_primitives}] triggered primitives!")

        not_completed_triggers_map = {
            Constants.TRIGGER_PRIMITIVE_NOT_COMPLETED_RPT_TIMEOUT: 0,
            Constants.TRIGGER_PRIMITIVE_NOT_COMPLETED_REDIRECT: 0,
            Constants.TRIGGER_PRIMITIVE_NOT_COMPLETED_RECEIPT_KO: 0,
            Constants.TRIGGER_PRIMITIVE_NOT_COMPLETED_ECOMMERCE_TIMEOUT: 0,
            Constants.TRIGGER_PRIMITIVE_NOT_COMPLETED_PAYMENTTOKEN_TIMEOUT: 0,
            Constants.TRIGGER_PRIMITIVE_NOT_COMPLETED_NO_STATE: 0,
        }
        for business_process in not_completed_triggers:
            if business_process in not_completed_triggers_map:
                not_completed_triggers_map[business_process] += 1

        return (completed_payments, not_completed_payments, not_completed_triggers_map, len(completed_carts), len(completed_no_carts))
    """
            
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
        logging.info(f"\t[INFO ][Extractor      ] Retrieved {grouped_trigger_requests.carts.count + grouped_trigger_requests.no_carts.count} primitives! Executing read for statistics about payments and receipts...")
        (completed_payments, not_completed_payments, not_completed_trigger_primitives, completed_carts, completed_no_carts) = self._extract_payment_status_tracking_by_session_id(grouped_trigger_requests, db_client)
        logging.info(f"\t[INFO ][Extractor      ] Retrieved statistics about payments and receipts! Finalizing report persistence...")

        # Generate statistics about trigger primitives
        numeric_data.trigger_primitives = TriggerPrimitiveReportInfo(carts_completed=completed_carts,
                                                                     carts_total=grouped_trigger_requests.carts.count, 
                                                                     no_carts_completed=completed_no_carts,
                                                                     no_carts_total=grouped_trigger_requests.no_carts.count,
                                                                     not_completed_rpt_timeout=not_completed_trigger_primitives[Constants.TRIGGER_PRIMITIVE_NOT_COMPLETED_RPT_TIMEOUT],
                                                                     not_completed_redirect=not_completed_trigger_primitives[Constants.TRIGGER_PRIMITIVE_NOT_COMPLETED_REDIRECT],
                                                                     not_completed_receipt_ko=not_completed_trigger_primitives[Constants.TRIGGER_PRIMITIVE_NOT_COMPLETED_RECEIPT_KO],
                                                                     not_completed_ecommerce_timeout=not_completed_trigger_primitives[Constants.TRIGGER_PRIMITIVE_NOT_COMPLETED_ECOMMERCE_TIMEOUT],
                                                                     not_completed_paymenttoken_timeout=not_completed_trigger_primitives[Constants.TRIGGER_PRIMITIVE_NOT_COMPLETED_PAYMENTTOKEN_TIMEOUT],
                                                                     not_completed_no_state=not_completed_trigger_primitives[Constants.TRIGGER_PRIMITIVE_NOT_COMPLETED_NO_STATE])
            
        # Generate statistics about completed payments
        numeric_data.completed_payments = CompletedPaymentsReportInfo(closed_as_ok=completed_payments.with_ok_receipts_all_sent,
                                                                      closed_as_ko=completed_payments.with_ko_receipts_all_sent)
            
        # Generate statistics about not completed payments
        numeric_data.not_completed_payments = NotCompletedPaymentsReportInfo(not_sent_end_retry=not_completed_payments.not_sent_end_retry,
                                                                             rejected=not_completed_payments.rejected,
                                                                             scheduled=not_completed_payments.sending_or_scheduled,
                                                                             ongoing=not_completed_payments.ongoing,
                                                                             never_sent=not_completed_payments.never_sent)
        return numeric_data
    

    # Generate numeric values to be set on weekly report 
    def _generate_numeric_data_for_weekly_report(self, db_client: WispDismantlingDatabase):
        week_days = Utility.get_week_in_date(self.date)
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

    