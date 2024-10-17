import logging
import os

from logic.clients import WispDismantlingDatabase
from utility.constants import Constants
from utility.utility import Utility
from datastructs.configuration import Configuration
from logic.slack_webhook import SlackWebhookHandler
from logic.extraction import Extractor


# setting logging level
logging.basicConfig(level=logging.INFO)

# retrieving passed environment variables
report_type = os.getenv("REPORT_TYPE", Constants.DAILY).lower()
date = os.getenv("REPORT_DATE", "")
if date == "":
    date = Utility.get_yesterday_date()
    logging.info(f"\t[INFO ][ExtractReport  ] No date passed. Using yesterday date [{date}] as starting date.")
logging.info(f"\t[INFO ][SendReport     ] Starting notification handling for date [{date}] for type [{report_type}].")

# initialize parameters
parameters = Utility.load_parameters()

# initialize configuration 
configuration = Configuration(
    parameters=parameters,
    env=os.getenv("REPORT_ENV", "prod"),
    slack_webhook_url=os.getenv("REPORT_SLACK_WEBHOOK_URL"),
    dataexplorer_url=os.getenv("REPORT_DATAEXPLORER_URL"),
    dataexplorer_clientid=os.getenv("REPORT_DATAEXPLORER_CLIENT_ID"),
    dataexplorer_clientsecret=os.getenv("REPORT_DATAEXPLORER_CLIENT_SECRET"),
    dataexplorer_tenantid=os.getenv("REPORT_DATAEXPLORER_TENANT_ID"),
    cosmosdb_url=os.getenv("REPORT_DATABASE_URL"),
    cosmosdb_key=os.getenv("REPORT_DATABASE_KEY"),
    cosmosdb_region=os.getenv("REPORT_DATABASE_REGION", "North Europe"),
    apiconfig_cache_subkey=os.getenv("REPORT_APICONFIG_CACHE_SUBKEY"),
)

# initialize clients
slack_webhook = SlackWebhookHandler(configuration=configuration)
db_client = WispDismantlingDatabase(configuration=configuration)

# get previously stored report entity
report_entity = db_client.retrieve_report(date, report_type)

# if report entity is found, send notification
if report_entity is not None:

    # generate stringfied form of report
    extractor = Extractor(date, parameters, report_type)
    report = extractor.generate_report_notification_detail(report_entity)

    # send notification via Slack WebHook
    slack_webhook.send_notification(report, report_type)
    logging.info(f"\t[INFO ][SendReport     ] Notification handling for date [{date}] for type [{report_type}] ended!")

else:
    logging.info(f"\t[INFO ][SendReport     ] Notification handling for date [{date}] for type [{report_type}] not executed! No generated report for this date was found.")