# Steps to execute in local environments

## Create and use virtual environment

```bash
python3 -m venv .env
source .env/bin/activate
```

## Install dependencies

```bash
pip install -r requirements.txt
```

## Run script

```bash
export REPORT_ENV="uat" \
    REPORT_TYPE="daily|weekly|monthly" \
    REPORT_DATE="yyyy-MM-dd" \
    REPORT_SLACK_WEBHOOK_URL="<SLACK_WEBHOOK_URL>" \
    REPORT_DATAEXPLORER_URL="<DATAEXPLORER_URL>" \
    REPORT_DATAEXPLORER_CLIENT_ID="<DATAEXPLORER_CLIENT_ID>" \
    REPORT_DATAEXPLORER_CLIENT_SECRET="<DATAEXPLORER_CLIENT_SECRET>" \
    REPORT_DATAEXPLORER_TENANT_ID="<DATAEXPLORER_TENANT_ID>" \
    REPORT_DATABASE_URL="<DATABASE_URL>" \
    REPORT_DATABASE_KEY="<DATABASE_KEY>" \
    REPORT_DATABASE_REGION="<REGION>" \
    REPORT_APICONFIG_CACHE_SUBKEY="<APICONFIG_CACHE_SUBKEY>"
python3 run_extraction.py
python3 run_send.py
```