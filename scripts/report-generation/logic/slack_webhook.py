import logging, math
from datastructs.configuration import Configuration
from datastructs.report import Report, ReportNotificationDetail
from slack_sdk.webhook import WebhookClient

from utility.constants import Constants


def _construct_report_header(title, emoji_name):
    return [
        {
            "type": "header",
            "text": {
                "type": "plain_text",
                "text": f":{emoji_name}: {title} :{emoji_name}:"
            }
        },
		{
			"type": "divider"
		}
    ]


def _construct_generic_section(title, message):
    return [
        {
            "type": "header",
            "text": {
                "type": "plain_text",
                "text": f"{title}"
            }
        },
        {
            "type": "section",
            "text": {
                "type": "mrkdwn",
                "text": f"{message}"
            }
        }
    ]

def _construct_info_section(message):
    return [
        {
            "type": "section",
            "text": {
                "type": "mrkdwn",
                "text": f"{message}"
            }
        }
    ]


class SlackWebhookHandler:

    def __init__(self, configuration: Configuration):
        # guard checks on configuration
        assert configuration is not None, "No valid Configuration is passed for WISP Dismantling's DB client generation"
        assert configuration.slack_webhook_url is not None, "No valid Slack Webhook URL set in Configuration"

        # initialize Slack WebHook
        self.webhook = WebhookClient(configuration.slack_webhook_url)


    def send_notification(self, report: ReportNotificationDetail, type):
        if report is not None:
            if type == Constants.DAILY:
                self._send_notification_for_daily_report(report)
            elif type == Constants.WEEKLY:
                self._send_notification_for_weekly_report(report)
            elif type == Constants.MONTHLY:
                self._send_notification_for_monthly_report(report)
            else:
                logging.warning(f"\t[WARN ][SlackWebHookHan] No valid report type set, no data is sent to Slack.")
        else:
            logging.warning(f"\t[WARN ][SlackWebHookHan] No valid report statistics set, no data is sent to Slack.")
            

    def _send_notification_for_daily_report(self, report: ReportNotificationDetail):
        self._send_notification_for_report(report, Constants.DAILY_REPORT_TITLE.format(report.date), "newspaper")


    def _send_notification_for_weekly_report(self, report: ReportNotificationDetail):
        self._send_notification_for_report(report, Constants.WEEKLY_REPORT_TITLE.format(report.date.replace("_", " - ")), "rolled_up_newspaper")


    def _send_notification_for_monthly_report(self, report: ReportNotificationDetail):
        self._send_notification_for_report(report, Constants.MONTHLY_REPORT_TITLE.format(report.date.replace("_", " - ")), "ledger")


    def _send_notification_for_report(self, report: ReportNotificationDetail, header, emoji_header):
        message_blocks = []
        message_blocks.extend(_construct_report_header(header, emoji_header))
        message_blocks.extend(_construct_info_section(report.notes))
        message_blocks.extend(_construct_generic_section(":bar_chart: Info generali :bar_chart:", report.general))
        message_blocks.extend(_construct_generic_section(":money_with_wings: Focus su Dismissione WISP :money_with_wings:", report.payments))
        
        response = self.webhook.send(text=Constants.SLACK_MSG_SUMMARY, blocks=message_blocks)
        logging.info(f"\t[INFO ][SlackWebHookHan] Response from Slack: [{response.status_code}] -> {response.body}")