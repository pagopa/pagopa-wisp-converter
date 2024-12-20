from datetime import datetime, timedelta
from utility.constants import Constants
import json, logging


class Utility:

    def load_parameters():
        configurations = {}
        try:
            with open(Constants.PARAMETERS_FILENAME, 'r') as file:
                configurations = json.load(file)
        except Exception as ex:
            logging.error(f"\t[ERROR][Utility        ] Error during parameter read from '{Constants.PARAMETERS_FILENAME}' file.")
        return configurations


    def get_report_id(date, type):
        assert date is not None, "Passed invalid date to report ID generator"
        assert type is not None, "Passed invalid type to report ID generator"
        formatted_date = Utility.get_report_date(date, type)
        return f"{formatted_date}_{type}"
    

    def get_report_date(date, type):
        report_date = date
        if type == Constants.WEEKLY:
            days = Utility.get_week_in_date(date)
            report_date = f"{days[0]}_{days[-1]}"
        elif type == Constants.MONTHLY:
            days = Utility.get_month_before_date(date)
            report_date = f"{days[0]}_{days[-1]}"
        return report_date


    def get_yesterday_date():
        today = datetime.today()
        yesterday = today - timedelta(days=1)
        return yesterday.strftime('%Y-%m-%d')


    def get_now_datetime():
        today = datetime.today()
        return today.strftime('%Y-%m-%d %H:%M:%S')
    

    def get_days_after_date(date, number_of_days_before):
        passed_date = datetime.strptime(date, "%Y-%m-%d")
        days_after_date = passed_date + timedelta(days=1)
        days = []
        days.append(date)
        days.extend([(days_after_date + timedelta(days=i)).strftime('%Y-%m-%d') for i in range(number_of_days_before)])
        return days
    

    def get_week_in_date(date):
        passed_date = datetime.strptime(date, "%Y-%m-%d")
        week_start = passed_date - timedelta(days=passed_date.weekday())
        return [(week_start + timedelta(days=i)).strftime('%Y-%m-%d') for i in range(7)]

    

    def get_month_before_date(date):        
        passed_date = datetime.strptime(date, "%Y-%m-%d")
        first_day_current_month = passed_date.replace(day=1)
        last_day_last_month = first_day_current_month - timedelta(days=1)
        first_day_last_month = last_day_last_month.replace(day=1)
        return [(first_day_last_month + timedelta(days=i)).strftime('%Y-%m-%d') for i in range((last_day_last_month - first_day_last_month).days + 1)]
    
     
    def safe_divide(numerator, denominator):
        value = 0
        if denominator != 0:
            value = numerator / denominator 
        return value