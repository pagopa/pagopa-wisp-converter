import logging
from azure.cosmos import CosmosClient, exceptions
from azure.cosmos.documents import ConnectionPolicy
from azure.kusto.data import KustoConnectionStringBuilder, KustoClient
from datastructs.configuration import Configuration
from datastructs.entities import ReceiptRTEntity, REEventEntity, ReportEntity, TriggerPrimitiveEntity
from datastructs.report import Report
from utility.constants import Constants
from utility.utility import Utility

import requests


class WispDismantlingDatabase:

    def __init__(self, configuration: Configuration):
        # guard checks on configuration
        assert configuration is not None, "No valid Configuration is passed for WISP Dismantling's DB client generation"
        assert configuration.dataexplorer_url is not None, "No valid Data Explorer URL set in Configuration"
        assert configuration.dataexplorer_clientid is not None, "No valid Data Explorer client ID in Configuration"
        assert configuration.dataexplorer_clientsecret is not None, "No valid Data Explorer client secret set in Configuration"
        assert configuration.dataexplorer_tenantid is not None, "No valid Data Explorer tenant ID set in Configuration"
        assert configuration.cosmosdb_url is not None, "No valid DB URL set in Configuration"
        assert configuration.cosmosdb_key is not None, "No valid DB key set in Configuration"

        # initialize Data Explorer
        dataexplorer_connection_string = KustoConnectionStringBuilder.with_aad_application_key_authentication(configuration.dataexplorer_url, 
                                                                                                 configuration.dataexplorer_clientid, 
                                                                                                 configuration.dataexplorer_clientsecret, 
                                                                                                 configuration.dataexplorer_tenantid)
        self.__dataexplorer_client = KustoClient(dataexplorer_connection_string)

        # initialize database
        connection_policy = ConnectionPolicy()
        connection_policy.PreferredLocations = [configuration.cosmosdb_region]
        client = CosmosClient(configuration.cosmosdb_url,
                              credential=configuration.cosmosdb_key,
                              connection_policy=connection_policy)
        self.__database = client.get_database_client(Constants.DATABASE_NAME)
        logging.info("\t[INFO ][WispDismantDB  ] Data Explorer configuration completed!")

        # initialize all needed containers
        self.__configuration_container = self.__database.get_container_client(Constants.CONFIGURATION_CONTAINER_NAME)
        self.__re_container = self.__database.get_container_client(Constants.RE_CONTAINER_NAME)
        self.__receipts_rt_container = self.__database.get_container_client(Constants.RECEIPTS_RT_CONTAINER_NAME)
        self.__report_container = self.__database.get_container_client(Constants.REPORTS_CONTAINER_NAME)
        self.__trigger_primitive_container = self.__database.get_container_client(Constants.TRIGGER_PRIMITIVE_CONTAINER_NAME)
        logging.info("\t[INFO ][WispDismantDB  ] DB configuration completed!")


    def read_ndp_payments_by_date(self, date):
        count = 0
        query = f'''ReEvent
            | where insertedTimestamp >= todatetime("{date}T00:00:00")
            | where insertedTimestamp <= todatetime("{date}T23:59:59")
            | where tipoEvento == 'nodoInviaRPT' or tipoEvento == 'nodoInviaCarrelloRPT'
            | where sottoTipoEvento == 'REQ'
            | count
        '''

        try:
            response = self.__dataexplorer_client.execute("re", query)
            result_table = response.primary_results[0]
            for row in result_table:
                count = row[0]

        except Exception as ex:
            logging.error(f"\t[ERROR][WispDismantDB  ] Error during read NdP's RE events in Data Explorer. The count of NdP primitives is set to zero.\n Exception: {ex}")

        return count


    def store_report(self, report: Report, type):
        
        numeric_data = report.numeric_data
        if numeric_data is None:
            logging.info(f"\t[WARN ][WispDismantDB  ] No numeric data generated. No data will be persisted for report [{type}] of date [{report.date}]")
        else:
            entity = ReportEntity(id=Utility.get_report_id(report.date, type),
                                  date=Utility.get_report_date(report.date, type),
                                  total_payments_on_ndp=numeric_data.total_payments_on_ndp,
                                  creditor_institutions=numeric_data.creditor_institutions,
                                  trigger_primitive=numeric_data.trigger_primitives,
                                  completed_payments=numeric_data.completed_payments,
                                  not_completed_payments=numeric_data.not_completed_payments)
            self.persist_report(entity)
            
    
    def persist_report(self, entity):
        try:
            self.__report_container.upsert_item(body=entity.get_map())
        except exceptions.CosmosHttpResponseError as ex:
            logging.error(f"\t[ERROR][WispDismantDB  ] Error during persisting report in '{Constants.REPORTS_CONTAINER_NAME}' container. {ex.message}")
            raise ex
        

    def retrieve_report(self, date, type) -> ReportEntity:
        report_id = Utility.get_report_id(date, type)
        report_date = Utility.get_report_date(date, type)
        result = None

        try:
            item = self.__report_container.read_item(item=report_id, partition_key=report_date)
            result = ReportEntity.from_db_item(item)

        except exceptions.CosmosResourceNotFoundError as ex:
            logging.error(f"\t[WARN ][WispDismantDB  ] No report found with ID [{report_id}] and partition key [{report_date}]")
        except exceptions.CosmosHttpResponseError as ex:
            logging.error(f"\t[ERROR][WispDismantDB  ] Error during read from '{Constants.REPORTS_CONTAINER_NAME}' container. {ex.message}")

        return result


    def get_active_creditor_institution(self):
        query = "SELECT VALUE conf.content FROM conf WHERE conf.id = 'cis' OFFSET 0 LIMIT 1"
        result = set()

        try:
            query_items = self.__configuration_container.query_items(query=query, enable_cross_partition_query=True)
            for item in query_items:
                result.update(item.split(','))

        except exceptions.CosmosHttpResponseError as ex:
            logging.error(f"\t[ERROR][WispDismantDB  ] Error during execution of query [{query}] in '{Constants.CONFIGURATION_CONTAINER_NAME}' container. {ex.message}")
            raise ex

        return result
    

    def get_trigger_primitive_by_date(self, date):
        query = f"SELECT * FROM data WHERE data.PartitionKey = '{date}'"
        result = []

        try:
            query_items = self.__trigger_primitive_container.query_items(query=query, enable_cross_partition_query=True)
            for item in query_items:
                entity = TriggerPrimitiveEntity.from_db_item(item)
                result.append(entity)

        except exceptions.CosmosHttpResponseError as ex:
            logging.error(f"\t[ERROR][WispDismantDB  ] Error during execution of query [{query}] in '{Constants.TRIGGER_PRIMITIVE_CONTAINER_NAME}' container. {ex.message}")
            raise ex

        return result
    

    def get_payment_status_by_re_events(self, session_id):
        query = f"SELECT * FROM re WHERE re.sessionId = '{session_id}' AND re.status IN ('RT_SEND_SUCCESS', 'RT_SEND_FAILURE')"
        result = []

        try:
            query_items = self.__re_container.query_items(query=query, enable_cross_partition_query=True)
            for item in query_items:
                entity = REEventEntity.from_db_item(item)
                result.append(entity)

        except exceptions.CosmosHttpResponseError as ex:
            logging.error(f"\t[ERROR][WispDismantDB  ] Error during execution of query [{query}] in '{Constants.RE_CONTAINER_NAME}' container. {ex.message}")
            raise ex

        return result
    

    def get_rt_trigger_by_re_events(self, session_id):
        query = f"SELECT * FROM re WHERE re.sessionId = '{session_id}' AND re.status = 'COMMUNICATION_WITH_CREDITOR_INSTITUTION_PROCESSED' AND re.businessProcess IN ('rpt-timeout-trigger', 'redirect', 'ecommerce-hang-timeout-trigger', 'payment-token-timeout-trigger', 'receipt-ko', 'receipt-ok')"
        result = []

        try:
            query_items = self.__re_container.query_items(query=query, enable_cross_partition_query=True)
            for item in query_items:
                entity = REEventEntity.from_db_item(item)
                result.append(entity)

        except exceptions.CosmosHttpResponseError as ex:
            logging.error(f"\t[ERROR][WispDismantDB  ] Error during execution of query [{query}] in '{Constants.RE_CONTAINER_NAME}' container. {ex.message}")
            raise ex

        return result
    

    def get_receipts_by_session_id(self, session_id):
        query = f"SELECT * FROM receiptrt WHERE receiptrt.sessionId = '{session_id}'"
        result = []

        try:
            query_items = self.__receipts_rt_container.query_items(query=query, enable_cross_partition_query=True)
            for item in query_items:
                entity = ReceiptRTEntity.from_db_item(item)
                result.append(entity)

        except exceptions.CosmosHttpResponseError as ex:
            logging.error(f"\t[ERROR][WispDismantDB  ] Error during execution of query [{query}] in '{Constants.RECEIPTS_RT_CONTAINER_NAME}' container. {ex.message}")
            raise ex

        return result


# ============================================================================================================

class APIConfigCacheClient:

    def __init__(self, configuration: Configuration):
        # guard checks on configuration
        assert configuration.env is not None, "No valid environment set in Configuration"
        assert configuration.apiconfig_cache_subkey is not None, "No valid subscription key for APIConfig Cache set in Configuration"

        # initializing Base path
        env = configuration.env + "."
        if env == "prod.":
            env = ""
        self.__base_path = f"https://api.{env}platform.pagopa.it/api-config-cache/p/v1"
        self.__headers = {
            'Content-Type': 'application/json',
            'Ocp-Apim-Subscription-Key': configuration.apiconfig_cache_subkey
        }

    def get_creditor_institution_station(self):
        try:
            # executing request
            request_url = self.__base_path + "/cache"
            params = {
                "keys": "creditorInstitutionStations"
            }
            response = requests.get(url=request_url, headers=self.__headers, params=params)

            # Raises HTTPError for bad responses or return response as JSON
            response.raise_for_status()
            return response.json()

        except requests.exceptions.HTTPError as err:
            logging.error(f"\t[ERROR][APICfgCacheClnt] Error during execution of API call to APIConfig cache. An HTTP error occurred: {err}")

        except Exception as err:
            logging.error(f"\t[ERROR][APICfgCacheClnt] Error during execution of API call to APIConfig cache. A generic error occurred: {err}")

        return None
