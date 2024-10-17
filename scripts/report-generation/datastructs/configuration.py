import logging

class Parameters:

    def __init__(self):
        self.params = {}

    
    def add(self, name, value):
        self.params.update(name, value)

    
    def get(self, name):
        return self.params.get(name)


# ============================================================================
     
class Configuration:

    def __init__(self,
                 parameters=Parameters(),
                 env="uat", 
                 slack_webhook_url=None, 
                 dataexplorer_url=None,
                 dataexplorer_clientid=None,
                 dataexplorer_clientsecret=None,
                 dataexplorer_tenantid=None,
                 cosmosdb_url=None, 
                 cosmosdb_key=None,
                 cosmosdb_region=None,
                 apiconfig_cache_subkey=None):
        # setting variables
        self.parameters = parameters
        self.env = env
        self.slack_webhook_url = slack_webhook_url
        self.dataexplorer_url = dataexplorer_url
        self.dataexplorer_clientid = dataexplorer_clientid
        self.dataexplorer_clientsecret = dataexplorer_clientsecret
        self.dataexplorer_tenantid = dataexplorer_tenantid
        self.cosmosdb_url = cosmosdb_url
        self.cosmosdb_key = cosmosdb_key
        self.cosmosdb_region = cosmosdb_region
        self.apiconfig_cache_subkey = apiconfig_cache_subkey

        # Configure logging
        logging.getLogger('azure.core.pipeline.policies.http_logging_policy').setLevel(logging.WARNING)
        logging.getLogger('azure.identity._internal.decorators').setLevel(logging.WARNING)


