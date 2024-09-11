import base64
import copy
import re
import time
from urllib.parse import parse_qs, urlparse
from behave import *
import logging
from allure_commons._allure import attach
import behave as behave

import utility.session as session
import utility.utils as utils
import request_generator as requestgen
import constants as constants
import router as router



# ==============================================
# ============== System up step ================
# ==============================================

@given('a new session')
def generate_session(context):
  
    logging.debug("=======================================================")
    logging.debug("[Generate session] Start re-generating data on session")
    session.clear_session(context)

    # update context with request and edit session data
    session_data = copy.deepcopy(context.config.userdata.get("test_data"))
    session.set_test_data(context, session_data)
    session.set_flow_data(context, constants.SESSION_DATA_RAW_RPTS, [])
    session.set_flow_data(context, constants.SESSION_DATA_TRIGGER_PRIMITIVE, constants.PRIMITIVE_NODOINVIARPT) # default value

    logging.debug("[Clear session] End re-generating data on session")

# ==============================================

@given('systems up')
def system_up(context):
    
    responses = True

    if "systems up" not in context.precondition_cache:
        for key in context.config.userdata.get("services"):
            row = context.config.userdata.get("services").get(key)
            if row.get("healthcheck") is not None:
                url = row.get("url") + row.get("healthcheck")
                logging.debug(f"[Health check] calling: {key} -> {url}")
                subscription_key = row.get("subscription_key")
                headers = {'Content-Type': 'application/json'}
                if subscription_key is not None:
                    headers[constants.OCP_APIM_SUBSCRIPTION_KEY] = subscription_key
                status_code, _, _ = utils.execute_request(url, "get", headers, payload=None, type=constants.ResponseType.JSON)
                logging.debug(f"[Health check] Received response: {status_code}")
                responses &= (status_code == 200)

        if responses:
            context.precondition_cache.add("systems up")

    assert responses, f"health-check systems or subscription-key errors"

# ==============================================



# ==============================================
# =============== Generic steps ================
# ==============================================

@step('the execution of "{scenario_name}" was successful')
def step_impl(context, scenario_name):

    all_scenarios = [scenario for feature in context._runner.features for scenario in feature.walk_scenarios()]
    phase = ([scenario for scenario in all_scenarios if scenario_name in scenario.name] or [None])[0]
    text_step = ''.join([step.keyword + " " + step.name + "\n\"\"\"\n" + (step.text or '') + "\n\"\"\"\n" for step in phase.steps])
    context.execute_steps(text_step)

# ==============================================



# ==============================================
# =============== [GIVEN] steps ================
# ==============================================

@given('a waiting time of {time_in_seconds} second{notes}')
def wait_for_n_seconds(context, time_in_seconds, notes):

    session.set_skip_tests(context, False)
    logging.info(f"Waiting [{time_in_seconds}] second{notes}")
    time.sleep(int(time_in_seconds))
    logging.info(f"Wait time ended")

# ==============================================

@given('a cart of RPTs {note}')
def generate_empty_cart(context, note):

    # retrieve test_data in order to generate flow_data session data
    test_data = session.get_test_data(context)

    # set trigger primitive information
    session.set_flow_data(context, constants.SESSION_DATA_TRIGGER_PRIMITIVE, constants.PRIMITIVE_NODOINVIACARRELLORPT)
    
    # generate cart identifier and defining info about multibeneficiary cart on flow_data
    if "for multibeneficiary" in note:
        iuv = utils.generate_iuv(in_18digit_format=True)
        session.set_flow_data(context, constants.SESSION_DATA_CART_ID, utils.generate_cart_id(iuv, test_data['creditor_institution']))
        session.set_flow_data(context, constants.SESSION_DATA_CART_IS_MULTIBENEFICIARY, True)
        session.set_flow_data(context, constants.SESSION_DATA_CART_MULTIBENEFICIARY_IUV, iuv)
    
    # generate cart identifier and set multibeneficiary info to False on flow_data
    else:
        session.set_flow_data(context, constants.SESSION_DATA_CART_ID, utils.generate_cart_id(None, test_data['creditor_institution']))
        session.set_flow_data(context, constants.SESSION_DATA_CART_IS_MULTIBENEFICIARY, False)


# ==============================================

@given('a single RPT of type {payment_type} with {number_of_transfers} transfers of which {number_of_stamps} are stamps')
def generate_single_rpt(context, payment_type, number_of_transfers, number_of_stamps):

    session.set_skip_tests(context, False)
    if number_of_stamps == "none":
        number_of_stamps = "0"
    
    # force IUV definition if the RPT is part of multibeneficiary cart
    iuv = None
    is_multibeneficiary_cart = session.get_flow_data(context, constants.SESSION_DATA_CART_IS_MULTIBENEFICIARY)
    if is_multibeneficiary_cart is not None and is_multibeneficiary_cart == True:
        iuv = session.get_flow_data(context, constants.SESSION_DATA_CART_MULTIBENEFICIARY_IUV)
    
    # force CCP definition if the RPT is part of multibeneficiary cart
    ccp = None
    if is_multibeneficiary_cart:
        ccp = session.get_flow_data(context, constants.SESSION_DATA_CART_ID)

    # setting main info
    test_data = session.get_test_data(context)
    domain_id = test_data['creditor_institution'] 
    payee_institution = test_data['payee_institutions_1']

    # set valid payee institution if non-first RPT of multibeneficiary cart must be created 
    rpts = session.get_flow_data(context, constants.SESSION_DATA_RAW_RPTS)
    if is_multibeneficiary_cart:
        if len(rpts) == 1:
            other_payee_institution = test_data['payee_institutions_2']
            domain_id = other_payee_institution['fiscal_code']
            payee_institution = other_payee_institution
        elif len(rpts) == 2:
            other_payee_institution = test_data['payee_institutions_3']
            domain_id = other_payee_institution['fiscal_code']
            payee_institution = other_payee_institution
       
    # generate raw RPT that will be used for construct XML content
    rpt = requestgen.create_rpt(test_data, iuv, ccp, domain_id, payee_institution, payment_type, int(number_of_transfers), int(number_of_stamps))
    
    # update the list of generated raw RPTs
    rpts.append(rpt)
    session.set_flow_data(context, constants.SESSION_DATA_RAW_RPTS, rpts)

# ==============================================

@given('an existing payment position related to {index} RPT with segregation code equals to {segregation_code} and state equals to {payment_status}')
def generate_payment_position(context, index, segregation_code, payment_status):

    session.set_skip_tests(context, False)

    # retrieve correct RPT from context in order to generate a payment position from it
    raw_rpts = session.get_flow_data(context, constants.SESSION_DATA_RAW_RPTS)
    payment_notice_index = utils.get_index_from_cardinal(index)
    rpt = raw_rpts[payment_notice_index]

    # generate payment position from extracted RPT
    payment_positions = requestgen.generate_gpd_paymentposition(context, rpt, segregation_code, payment_status)

    # if payment_status is VALID, the retrieved URL will contains toPublish flag set as true
    if payment_status == "VALID":
        base_url, subkey = router.get_rest_url(context, "create_paymentposition_and_publish")
    
    # if payment_status is not VALID, the retrieved URL will contains toPublish flag set as false
    else:
        base_url, subkey = router.get_rest_url(context, "create_paymentposition")

    # initialize API call and get response
    url = base_url.format(creditor_institution=rpt['domain']['id'])
    headers = {'Content-Type': 'application/json', constants.OCP_APIM_SUBSCRIPTION_KEY: subkey}
    req_description = constants.REQ_DESCRIPTION_CREATE_PAYMENT_POSITION.format(step=context.running_step)
    status_code, _, _ = utils.execute_request(url, "post", headers, payment_positions, type=constants.ResponseType.JSON, description=req_description)

    # executing assertions
    utils.assert_show_message(status_code == 201, f"The debt position for RPT with index [{index}] was not created. Expected status code [201], Current status code [{status_code}]")

# ==============================================

@given('a valid nodoInviaRPT request')
def generate_nodoinviarpt(context):
    
    session.set_skip_tests(context, False)

    # retrieve test_data in order to generate flow_data session data
    test_data = session.get_test_data(context)
    
    # generate nodoInviaRPT request from raw RPT
    raw_rpts = session.get_flow_data(context, constants.SESSION_DATA_RAW_RPTS)
    request = requestgen.generate_nodoinviarpt(test_data, raw_rpts[0])
    
    # update context with request and edit flow_data
    session.set_flow_data(context, constants.SESSION_DATA_REQ_BODY, request)

# ==============================================

@given('a valid nodoInviaCarrelloRPT request{options}')
def generate_nodoinviacarrellorpt(context, options):
    
    session.set_skip_tests(context, False)

    # retrieve test_data in order to generate flow_data session data
    test_data = session.get_test_data(context)

    # retrieve info about multibeneficiary status
    rpts = session.get_flow_data(context, constants.SESSION_DATA_RAW_RPTS)
    cart_id = session.get_flow_data(context, constants.SESSION_DATA_CART_ID)
    is_multibeneficiary = session.get_flow_data(context, constants.SESSION_DATA_CART_IS_MULTIBENEFICIARY)

    # set channel and password regarding the required options
    channel = test_data['channel_wisp']
    password = test_data['channel_wisp_password']
    psp = test_data['psp_wisp']
    psp_broker = test_data['psp_broker_wisp']
    if "WFESP channel" in options:
        channel = test_data['channel_wfesp']
        password = test_data['channel_wfesp_password']
        psp = test_data['psp_wfesp']
        psp_broker = test_data['psp_broker_wfesp']

    # generate nodoInviaCarrelloRPT request from raw RPTs and info about multibeneficiary status
    request = requestgen.generate_nodoinviacarrellorpt(test_data, cart_id, rpts, psp, psp_broker, channel, password, is_multibeneficiary)    
    
    logging.debug("\n\n==request==")
    logging.debug(request)

    # update context with request to be sent
    session.set_flow_data(context, constants.SESSION_DATA_REQ_BODY, request)

# ==============================================

@given('a valid checkPosition request')
def generate_checkposition(context):

    session.set_skip_tests(context, False)

    # generate checkPosition request from retrieved payment notices
    payment_notices = session.get_flow_data(context, constants.SESSION_DATA_PAYMENT_NOTICES)
    request = requestgen.generate_checkposition(payment_notices)

    # update context with request to be sent
    session.set_flow_data(context, constants.SESSION_DATA_REQ_BODY, request)

# ==============================================

@given('a valid activatePaymentNoticeV2 request on {index} payment notice')
def generate_activatepaymentnotice(context, index):

    session.set_skip_tests(context, False)

    # retrieve test_data in order to generate flow_data session data
    test_data = session.get_test_data(context)

    # retrieve session id previously generated in redirect call
    session_id = session.get_flow_data(context, constants.SESSION_DATA_SESSION_ID)
    
    # retrieve payment notices in order to generate request
    payment_notice_index = utils.get_index_from_cardinal(index)
    payment_notices = session.get_flow_data(context, constants.SESSION_DATA_PAYMENT_NOTICES)

    # check if payment notice at passed index exists
    if payment_notice_index + 1 > len(payment_notices):
        session.set_skip_tests(context, True)
        return

    # generate activatePaymentNoticeV2 request from retrieved payment notices
    rpts = session.get_flow_data(context, constants.SESSION_DATA_RAW_RPTS)
    request = requestgen.generate_activatepaymentnotice(test_data, payment_notices, rpts[payment_notice_index], session_id) 

    # update context with request to be sent
    session.set_flow_data(context, constants.SESSION_DATA_REQ_BODY, request)

# ==============================================

@given('a valid closePaymentV2 request with outcome {outcome}')
def generate_closepayment(context, outcome):
    
    session.set_skip_tests(context, False)

    # retrieve test_data in order to generate flow_data session data
    test_data = session.get_test_data(context)

    # generate request
    payment_notices = session.get_flow_data(context, constants.SESSION_DATA_PAYMENT_NOTICES)

    # generate closePaymentV2 request from retrieved raw RPTs and payment notices
    rpts = session.get_flow_data(context, constants.SESSION_DATA_RAW_RPTS)
    request = requestgen.generate_closepayment(test_data, payment_notices, rpts, outcome)

    # update context with request to be sent
    session.set_flow_data(context, constants.SESSION_DATA_REQ_BODY, request)

# ==============================================

@given('a valid session identifier to be redirected to WISP dismantling')
def get_valid_sessionid(context):

    session.set_skip_tests(context, False)

    # retrieve session id previously generated in redirect call
    session_id = session.get_flow_data(context, constants.SESSION_DATA_SESSION_ID)
    
    # executing assertions
    utils.assert_show_message(len(session_id) == 36, f"The session ID must consist of a UUID only. Session ID: [{session_id}]")

# ==============================================

@given('the {index} IUV code of the sent RPTs')
def get_iuv_from_session(context, index):

    session.set_skip_tests(context, False)

    # retrieve raw RPTs from context
    rpt_index = utils.get_index_from_cardinal(index)
    raw_rpts = session.get_flow_data(context, constants.SESSION_DATA_RAW_RPTS)
    
    # check if IUV at passed index exists
    if rpt_index + 1 > len(raw_rpts):
        session.set_skip_tests(context, True)
        return
    
    # update IUV structure with the one retrieved from raw RPTs
    iuv = raw_rpts[rpt_index]['payment_data']['iuv']
    iuvs = session.get_flow_data(context, constants.SESSION_DATA_IUVS)
    if iuvs is None:
        iuvs = [None, None, None, None, None]
    iuvs[rpt_index] = iuv

    # update context with IUVs to be sent
    session.set_flow_data(context, constants.SESSION_DATA_IUVS, iuvs)

# ==============================================

@given('all the IUV codes of the sent RPTs')
def get_iuvs_from_session(context):

    session.set_skip_tests(context, False)

    # retrieve raw RPTs from context
    raw_rpts = session.get_flow_data(context, constants.SESSION_DATA_RAW_RPTS)
        
    # update IUV structure with all the ones retrieved from raw RPTs
    iuvs = session.get_flow_data(context, constants.SESSION_DATA_IUVS)
    if iuvs is None:
        iuvs = [None, None, None, None, None]
    rpt_index = 0
    for raw_rpt in raw_rpts:
        iuv = raw_rpt['payment_data']['iuv']
        iuvs[rpt_index] = iuv
        rpt_index += 1

    # update context with IUVs to be sent    
    session.set_flow_data(context, constants.SESSION_DATA_IUVS, iuvs)

# ==============================================

@given('the same nodoInviaCarrelloRPT for another try')
def update_old_nodoInviaCarrelloRPT_request(context):
    
    # change cart identifier editing last char value
    cart_id = session.get_flow_data(context, constants.SESSION_DATA_CART_ID)
    session.set_flow_data(context, constants.SESSION_DATA_CART_ID, utils.change_last_numeric_char(cart_id))

    # change all CCPs content editing last char value
    rpts = session.get_flow_data(context, constants.SESSION_DATA_RAW_RPTS)
    for rpt in rpts:
        ccp = rpt['payment_data']['ccp']
        rpt['payment_data']['ccp'] = utils.change_last_numeric_char(ccp)
    
    # update context with request and edit flow_data
    session.set_flow_data(context, constants.SESSION_DATA_RAW_RPTS, rpts)

# ==============================================



# ==============================================
# ================ [WHEN] steps ================
# ==============================================

@when('the {actor} sends a {primitive} action')
def send_primitive(context, actor, primitive):

    # skipping this step if its execution is not required
    if session.skip_tests(context):
        logging.debug("Skipping send_primitive step")
        return

    # retrieve generated request from context in order to execute the API call 
    request = session.get_flow_data(context, constants.SESSION_DATA_REQ_BODY)

    # initialize API call and get response
    url, subkey, content_type = router.get_primitive_url(context, primitive)
    headers = {}
    if content_type == constants.ResponseType.XML:
        headers = {'Content-Type': 'application/xml', 'SOAPAction': primitive, constants.OCP_APIM_SUBSCRIPTION_KEY: subkey}
    elif content_type == constants.ResponseType.JSON:
        headers = {'Content-Type': 'application/json', constants.OCP_APIM_SUBSCRIPTION_KEY: subkey}
    req_description = constants.REQ_DESCRIPTION_EXECUTE_SOAP_CALL.format(step=context.running_step)
    status_code, body_response, _ = utils.execute_request(url, "post", headers, request, content_type, description=req_description)

    # update context setting all information about response
    session.set_flow_data(context, constants.SESSION_DATA_RES_CODE, status_code)
    session.set_flow_data(context, constants.SESSION_DATA_RES_BODY, body_response)
    session.set_flow_data(context, constants.SESSION_DATA_RES_CONTENTTYPE, content_type)

# ==============================================

@when('the user continue the session in WISP dismantling')
def send_sessionid_to_wispdismantling(context):

    # initialize API call and get response
    url, _ = router.get_rest_url(context, "redirect")    
    headers = {'Content-Type': 'application/xml'}
    sessionId = session.get_flow_data(context, constants.SESSION_DATA_SESSION_ID)
    url += sessionId
    req_description = constants.REQ_DESCRIPTION_EXECUTE_CALL_TO_WISPCONV.format(step=context.running_step, sessionId=sessionId)
    status_code, response_body, response_headers = utils.execute_request(url, "get", headers, type=constants.ResponseType.HTML, allow_redirect=False, description=req_description)
    
    # update context setting all information about response
    if 'Location' in response_headers:
        location_header = response_headers['Location']
        attach(location_header, name="Received response")
        session.set_flow_data(context, constants.SESSION_DATA_RES_BODY, location_header)
    else:
        attach(response_body, name="Received response")
        session.set_flow_data(context, constants.SESSION_DATA_RES_BODY, response_body)
    session.set_flow_data(context, constants.SESSION_DATA_RES_CODE, status_code)    
    session.set_flow_data(context, constants.SESSION_DATA_RES_CONTENTTYPE, constants.ResponseType.HTML)

# ==============================================

@when('the user searches for flow steps by IUVs')
def search_in_re_by_iuv(context):

    # skipping this step if its execution is not required
    if session.skip_tests(context):
        logging.debug("Skipping search_in_re_by_iuv step")
        return

    # retrieve and initialize information needed for next API execution
    iuvs = session.get_flow_data(context, constants.SESSION_DATA_IUVS)
    creditor_institution = session.get_test_data(context)['creditor_institution']
    today = utils.get_current_date()
    re_events = []

    # initialize API call main information
    base_url, subkey = router.get_rest_url(context, "search_in_re_by_iuv")
    headers = {'Content-Type': 'application/json', constants.OCP_APIM_SUBSCRIPTION_KEY: subkey}

    # for each iuv it is required to retrieve events from RE
    for iuv in iuvs:
        if iuv is not None:

            # initialize API call and get response
            url = base_url.format(creditor_institution=creditor_institution, iuv=iuv, date_from=today, date_to=today)
            req_description = constants.REQ_DESCRIPTION_RETRIEVE_EVENTS_FROM_RE.format(step=context.running_step, iuv=iuv)
            status_code, body_response, _ = utils.execute_request(url, "get", headers, type=constants.ResponseType.JSON, description=req_description)
            
            # executing assertions
            utils.assert_show_message('data' in body_response, f"The response does not contains data.")
            utils.assert_show_message(len(body_response['data']) > 0, f"There are not event data in the response.")
            
            # add received event on the list of whole events
            re_events.extend(body_response['data'])

    # update context setting all information about response
    session.set_flow_data(context, constants.SESSION_DATA_RES_CODE, status_code)
    session.set_flow_data(context, constants.SESSION_DATA_RES_BODY, re_events)
    
# ==============================================

@when('the user searches for payment position in GPD by {index} IUV')
def search_paymentposition_by_iuv(context, index):

    # retrieve payment notice from context in order to execute the API call 
    rpt_index = utils.get_index_from_cardinal(index)
    payment_notices = session.get_flow_data(context, constants.SESSION_DATA_PAYMENT_NOTICES)  

    # skipping this step if its execution is not required
    if rpt_index + 1 > len(payment_notices):
        session.set_skip_tests(context, True)
        return
  
    # retrieve data required for API call
    payment_notice = payment_notices[rpt_index]
    iuv = payment_notice['iuv']

    # initialize API call and get response
    base_url, subkey = router.get_rest_url(context, "get_paymentposition_by_iuv")
    url = base_url.format(creditor_institution=payment_notice['domain_id'], iuv=iuv)
    headers = {'Content-Type': 'application/json', constants.OCP_APIM_SUBSCRIPTION_KEY: subkey}
    req_description = constants.REQ_DESCRIPTION_RETRIEVE_PAYMENT_POSITION.format(step=context.running_step, iuv=iuv)
    status_code, body_response, _ = utils.execute_request(url, "get", headers, type=constants.ResponseType.JSON, description=req_description)

    # update context setting all information about response
    session.set_flow_data(context, constants.SESSION_DATA_RES_CODE, status_code)
    session.set_flow_data(context, constants.SESSION_DATA_RES_BODY, body_response)
    session.set_flow_data(context, constants.SESSION_DATA_RES_CONTENTTYPE, constants.ResponseType.JSON)
    
# ==============================================



# ==============================================
# ================ [THEN] steps ================
# ==============================================

@then('the {actor} receives the HTTP status code {status_code}')
def check_status_code(context, actor, status_code):

    # skipping this step if its execution is not required
    if session.skip_tests(context):
        logging.debug("Skipping check_status_code step")
        return
    
    # retrieve status code related to executed request
    status_code = session.get_flow_data(context, constants.SESSION_DATA_RES_CODE)
    
    # executing assertions
    utils.assert_show_message(status_code == int(status_code), f"The status code is not 200. Current value: {status_code}.")

# ==============================================

@then('the {actor} receives an HTML page with an error')
def check_html_error_page(context, actor):

    # retrieve response body related to executed request
    response = session.get_flow_data(context, constants.SESSION_DATA_RES_BODY)
    
    # executing assertions
    utils.assert_show_message('<!DOCTYPE html>' in response, f"The response is not an HTML page")
    utils.assert_show_message('Si &egrave; verificato un errore imprevisto' in response, f"The HTML page does not contains an error message.")

# ==============================================

@then('the response contains the field {field_name} with value {field_value}')
def check_field(context, field_name, field_value):

    # skipping this step if its execution is not required
    if session.skip_tests(context):
        logging.debug("Skipping check_field step")
        return
    
    # retrieve response information related to executed request
    field_value = field_value.replace('\'', '')    
    response = session.get_flow_data(context, constants.SESSION_DATA_RES_BODY)
    content_type = session.get_flow_data(context, constants.SESSION_DATA_RES_CONTENTTYPE)
    
    # executing assertions
    if content_type == constants.ResponseType.XML:
        field_value_in_object = response.find(f'.//{field_name}')
        utils.assert_show_message(field_value_in_object is not None, f"The field [{field_name}] does not exists.")
        field_value_in_object = field_value_in_object.text
    elif content_type == constants.ResponseType.JSON:
        field_value_in_object = utils.get_nested_field(response, field_name)
        utils.assert_show_message(field_value_in_object is not None, f"The field [{field_name}] does not exists.")
    utils.assert_show_message(field_value_in_object == field_value, f"The field [{field_name}] is not equals to {field_value}. Current value: {field_value_in_object}.")
    
# ==============================================

@then('the response contains the field {field_name} as not empty list')
def check_field(context, field_name):

    # retrieve response information related to executed request
    response = session.get_flow_data(context, constants.SESSION_DATA_RES_BODY)
    content_type = session.get_flow_data(context, constants.SESSION_DATA_RES_CONTENTTYPE)
   
    # executing assertions
    if content_type == constants.ResponseType.XML:
        field_value_in_object = response.find(f'.//{field_name}')
    elif content_type == constants.ResponseType.JSON:
        field_value_in_object = utils.get_nested_field(response, field_name)
    utils.assert_show_message(field_value_in_object is not None, f"The field [{field_name}] does not exists.")
    utils.assert_show_message(len(field_value_in_object) > 0, f"The field [{field_name}] is empty but is required to be not empty.")

# ==============================================

@then('the response contains the field {field_name} with non-null value')
def check_field(context, field_name):

    # skipping this step if its execution is not required
    if session.skip_tests(context):
        logging.debug("Skipping check_field step")
        return
    
    # retrieve response information related to executed request
    response = session.get_flow_data(context, constants.SESSION_DATA_RES_BODY)
    content_type = session.get_flow_data(context, constants.SESSION_DATA_RES_CONTENTTYPE)

    # executing assertions
    if content_type == constants.ResponseType.XML:
        field_value_in_object = response.find(f'.//{field_name}')
    elif content_type == constants.ResponseType.JSON:
        field_value_in_object = utils.get_nested_field(response, field_name)
    utils.assert_show_message(field_value_in_object is not None, f"The field [{field_name}] does not exists.")
    
# ==============================================

@then('there is a {business_process} event with field {field_name} with value {field_value}')
def check_event(context, business_process, field_name, field_value):

    # skipping this step if its execution is not required
    if session.skip_tests(context):
        logging.debug("Skipping check_event step")
        return
    
    # retrieve response information related to executed request
    re_events = session.get_flow_data(context, constants.SESSION_DATA_RES_BODY)

    # executing assertions
    needed_process_events = [re_event for re_event in re_events if 'businessProcess' in re_event and re_event['businessProcess'] == business_process]
    utils.assert_show_message(len(needed_process_events) > 0, f"There are not events with business process {business_process}.")
    needed_events = [re_event for re_event in needed_process_events if field_name in re_event and re_event[field_name] == field_value]
    utils.assert_show_message(len(needed_events) > 0, f"There are not events with business process {business_process} and field {field_name} with value [{field_value}].")
    
    # set needed events in context in order to be better analyzed in the next steps
    session.set_flow_data(context, constants.SESSION_DATA_LAST_ANALYZED_RE_EVENT, needed_events)

# ==============================================

@then('these events are related to each payment token')
def check_event_token_relation(context):

    # retrieve events and payment notices related to executed request
    needed_events = session.get_flow_data(context, constants.SESSION_DATA_LAST_ANALYZED_RE_EVENT)
    payment_notices = session.get_flow_data(context, constants.SESSION_DATA_PAYMENT_NOTICES)
    
    # executing assertions
    payment_tokens = [payment_notice['payment_token'] for payment_notice in payment_notices]
    for payment_token in payment_tokens:    
        utils.assert_show_message(any(event['paymentToken'] == payment_token for event in needed_events), f"The payment token {payment_token} is not correctly handled by the previous event.")

# ==============================================

@then('these events are related to each notice number')
def check_event_token_relation(context):

    # retrieve events and payment notices related to executed request
    needed_events = session.get_flow_data(context, constants.SESSION_DATA_LAST_ANALYZED_RE_EVENT)
    payment_notices = session.get_flow_data(context, constants.SESSION_DATA_PAYMENT_NOTICES)

    # executing assertions
    notice_numbers = [payment_notice['notice_number'] for payment_notice in payment_notices]
    for notice_number in notice_numbers:
        utils.assert_show_message(any(event['noticeNumber'] == notice_number for event in needed_events), f"The notice number {notice_number} is not correctly handled by the previous event.")

# ==============================================
@then('the response contains the {url_type} URL')
def check_redirect_url(context, url_type):

    # retrieve information related to executed request
    response = session.get_flow_data(context, constants.SESSION_DATA_RES_BODY)
    url = response.find('.//url')
    utils.assert_show_message(url is not None, f"The field 'redirect_url' in response doesn't exists.")
    extracted_url = url.text
    parsed_url = urlparse(extracted_url)
    query_params = parse_qs(parsed_url.query)
    id_session = query_params['idSession'][0] if len(query_params['idSession']) > 0 else None

    # executing assertions
    utils.assert_show_message(id_session is not None, f"The field 'idSession' in response is not correctly set.")
    if "redirect" in url_type:
        utils.assert_show_message("wisp-converter" in extracted_url, f"The URL is not the one defined for WISP dismantling.")
    elif "old WISP" in url_type:
        utils.assert_show_message("wallet" in extracted_url, f"The URL is not the one defined for old WISP.")
    elif "fake WFESP" in url_type:
        utils.assert_show_message("wfesp" in extracted_url, f"The URL is not the one defined for WFESP dismantling.")

    # set session identifier in context in order to be better analyzed in the next steps
    session.set_flow_data(context, constants.SESSION_DATA_SESSION_ID, id_session)

# ==============================================

@then('the user can be redirected to Checkout')
def check_redirect_url(context):

    # retrieve redirect URL extracted from executed request
    location_redirect_url = session.get_flow_data(context, constants.SESSION_DATA_RES_BODY)
    
    # executing assertions
    utils.assert_show_message(location_redirect_url is not None, f"The header 'Location' does not exists.")
    utils.assert_show_message("ecommerce/checkout" in location_redirect_url, f"The header 'Location' does not refers to Checkout service. {location_redirect_url}")
  
# ==============================================  

@then('all the related notice numbers can be retrieved')
def retrieve_payment_notice_from_re_event(context):

    # retrieve events elated to executed request
    re_events = session.get_flow_data(context, constants.SESSION_DATA_RES_BODY)

    # executing assertions
    needed_events = [re_event for re_event in re_events if 'status' in re_event and re_event['status'] == "SAVED_RPT_IN_CART_RECEIVED_REDIRECT_URL_FROM_CHECKOUT"]
    utils.assert_show_message(len(needed_events) > 0, f"The redirect process is not ended successfully or there are missing events in RE")
    notices = set([(re_event['domainId'], re_event['iuv'], re_event['noticeNumber']) for re_event in needed_events])
    utils.assert_show_message(len(notices) > 0, f"Impossible to extract payment notices from events in RE")
    
    # set updated payment notices in context in order to be better analyzed in the next steps
    payment_notices = []
    for payment_notice in notices:
        payment_notices.append({
            "domain_id": payment_notice[0],
            "iuv": payment_notice[1],
            "notice_number": payment_notice[2],
            "payment_token": None
        })
    session.set_flow_data(context, constants.SESSION_DATA_PAYMENT_NOTICES, payment_notices)

# ==============================================  

@then('the payment token can be retrieved and associated to {index} RPT')
def retrieve_payment_token_from_activatepaymentnotice(context, index):

    # skipping this step if its execution is not required
    if session.skip_tests(context):
        logging.debug("Skipping retrieve_payment_token_from_activatepaymentnotice step")
        return
    
    # retrieve information related to executed request
    rpt_index = utils.get_index_from_cardinal(index)
    response = session.get_flow_data(context, constants.SESSION_DATA_RES_BODY)
    field_value_in_object = response.find('.//paymentToken')
    
    # executing assertions
    payment_notices = session.get_flow_data(context, constants.SESSION_DATA_PAYMENT_NOTICES)
    utils.assert_show_message(len(payment_notices) >= rpt_index + 1, f"Not enough payment notices are defined in the session data for correctly point at index {rpt_index}.")
    payment_notice = payment_notices[rpt_index]
    utils.assert_show_message('iuv' in payment_notice, f"No valid payment is defined at index {rpt_index}.") 
    payment_notice['payment_token'] = field_value_in_object.text
    session.set_flow_data(context, constants.SESSION_DATA_PAYMENT_NOTICES, payment_notices)

# ==============================================  

@then('the response contains a single payment option')
def check_single_paymentoption(context):

    # skipping this step if its execution is not required
    if session.skip_tests(context):
        logging.debug("Skipping check_single_paymentoption step")
        return
    
    # retrieve information related to executed request
    response = session.get_flow_data(context, constants.SESSION_DATA_RES_BODY)

    # executing assertions
    utils.assert_show_message('paymentOption' in response, f"No field 'paymentOption' is defined for the retrieved payment position.") 
    payment_options = utils.get_nested_field(response, "paymentOption")
    utils.assert_show_message(len(payment_options) == 1, f"There is not only one payment option in the payment position. Found number {len(payment_options)}.") 

    # sorting transfer by transfer ID in order to avoid strange comparations
    transfers = payment_options[0]['transfer']
    transfers = sorted(transfers, key=lambda transfer: transfer['idTransfer'])
    payment_options[0]['transfer'] = transfers
    response['paymentOption'] = payment_options
    session.set_flow_data(context, constants.SESSION_DATA_RES_BODY, response)

# ==============================================  

@then('the response contains the payment option correctly generated from {index} RPT')
def check_paymentoption_amounts(context, index):

    # skipping this step if its execution is not required
    if session.skip_tests(context):
        logging.debug("Skipping check_paymentoption_amounts step")
        return

    # retrieve response information related to executed request
    response = session.get_flow_data(context, constants.SESSION_DATA_RES_BODY)    
    rpt_index = utils.get_index_from_cardinal(index)
    payment_options = utils.get_nested_field(response, "paymentOption")
    payment_option = payment_options[0]

    # retrieve payment notices and raw RPT in order to execute checks on data
    payment_notices = session.get_flow_data(context, constants.SESSION_DATA_PAYMENT_NOTICES)  
    payment_notice = payment_notices[rpt_index]
    rpts = session.get_flow_data(context, constants.SESSION_DATA_RAW_RPTS)
    rpt = [rpt for rpt in rpts if rpt['payment_data']['iuv'] == payment_notice['iuv']][0]
    payment_data = rpt['payment_data']

    # executing assertions
    utils.assert_show_message(response['pull'] == False, f"The payment option must be not defined for pull payments.")
    utils.assert_show_message(int(payment_option['amount']) == round(payment_data['total_amount'] * 100), f"The total amount calculated for {index} RPT is not equals to the one defined in GPD payment position. GPD's: [{int(payment_option['amount'])}], RPT's: [{round(payment_data['total_amount'] * 100)}]") 
    utils.assert_show_message(payment_option['notificationFee'] == 0, f"The notification fee in the {index} payment position defined for GPD must be always 0.") 
    utils.assert_show_message(payment_option['isPartialPayment'] == False, f"The payment option must be not defined as partial payment.") 

# ==============================================  

@then('the response contains the status in {status} for the payment option')
def check_paymentposition_status(context, status):

    # skipping this step if its execution is not required
    if session.skip_tests(context):
        logging.debug("Skipping check_paymentposition_status step")
        return
    
    # retrieve response information related to executed request
    response = session.get_flow_data(context, constants.SESSION_DATA_RES_BODY)
    payment_options = utils.get_nested_field(response, "paymentOption")
    payment_option = payment_options[0]

    # executing assertions
    utils.assert_show_message(payment_option['status'] == status, f"The payment option must be equals to [{status}]. Current status: [{payment_option['status']}]")

# ==============================================  

@then('the response contains the transfers correctly generated from RPT')
def check_paymentposition_transfers(context):

    # skipping this step if its execution is not required
    if session.skip_tests(context):
        logging.debug("Skipping check_paymentposition_transfers step")
        return
        
    # retrieve response information related to executed request
    response = session.get_flow_data(context, constants.SESSION_DATA_RES_BODY)
    payment_options = utils.get_nested_field(response, "paymentOption")
    transfers_from_po = payment_options[0]['transfer']

    # retrieve payment notices and raw RPT in order to execute checks on data
    raw_rpts = session.get_flow_data(context, constants.SESSION_DATA_RAW_RPTS)
    rpt = [rpt for rpt in raw_rpts if rpt['payment_data']['iuv'] == payment_options[0]['iuv']][0]
    transfers_from_rpt = rpt['payment_data']['transfers']

    # executing assertions
    utils.assert_show_message(len(transfers_from_po) == len(transfers_from_rpt), f"There are not the same amount of transfers. GPD's: [{len(transfers_from_po)}], RPT's: [{len(transfers_from_rpt)}]")
    for transfer_index in range(len(transfers_from_po)):
        transfer_from_po = transfers_from_po[transfer_index]
        transfer_from_rpt = transfers_from_rpt[transfer_index]
        utils.assert_show_message(transfer_from_po['status'] == "T_UNREPORTED", f"The status of the transfer {transfer_index} must be equals to [T_UNREPORTED]. Current status: [{transfer_from_po['status']}]")
        utils.assert_show_message(int(transfer_from_po['amount']) == round(transfer_from_rpt['amount'] * 100), f"The amount of the transfer {transfer_index} must be equals to the same defined in the payment position. GPD's: [{int(transfer_from_po['amount'])}], RPT's: [{round(transfer_from_rpt['amount'])}]")
        utils.assert_show_message('transferMetadata' in transfer_from_po and len(transfer_from_po['transferMetadata']) > 0, f"There are not transfer metadata in transfer {transfer_index} but at least one is required.")
        utils.assert_show_message('stamp' in transfer_from_po or 'iban' in transfer_from_po, f"There are either IBAN and stamp definition in transfer {transfer_index} but they cannot be defined together.")
        if transfer_from_rpt['is_mbd'] == True:
            utils.assert_show_message('stamp' in transfer_from_po, f"There is not stamp definition in transfer {transfer_index} but RPT transfer require it.")
            utils.assert_show_message('hashDocument' in transfer_from_po['stamp'], f"There is not a valid hash for stamp in transfer {transfer_index}.")
            utils.assert_show_message('stampType' in transfer_from_po['stamp'], f"There is not a valid type for stamp in transfer {transfer_index}.")
            utils.assert_show_message(transfer_from_po['stamp']['hashDocument'] == transfer_from_rpt['stamp_hash'], f"The hash defined for the stamp in payment position in transfer {transfer_index} is not equals to the one defined in RPT. GPD's: [{transfer_from_po['stamp']['hashDocument']}], RPT's: [{transfer_from_rpt['stamp_hash']}]")
            utils.assert_show_message(transfer_from_po['stamp']['stampType'] == transfer_from_rpt['stamp_type'], f"The type defined for the stamp in payment position in transfer {transfer_index} is not equals to the one defined in RPT. GPD's: [{transfer_from_po['stamp']['stampType']}], RPT's: [{transfer_from_rpt['stamp_type']}]")
        else:
            utils.assert_show_message('iban' in transfer_from_po, f"There is not IBAN definition in transfer {transfer_index} but RPT transfer require it.")    
            utils.assert_show_message(transfer_from_po['iban'] == transfer_from_rpt['creditor_iban'], f"The IBAN defined in transfer {transfer_index} is not equals to the one defined in RPT. GPD's: [{transfer_from_po['iban']}], RPT's: [{transfer_from_rpt['creditor_iban']}]")


# ==============================================  

@then('the response contains the payment option correctly generated from all RPTs')
def check_paymentoption_amounts_for_multibeneficiary(context):

    # retrieve response information related to executed request
    response = session.get_flow_data(context, constants.SESSION_DATA_RES_BODY)
    payment_options = utils.get_nested_field(response, "paymentOption")
    payment_option = payment_options[0]

    # calculate the correct amount of RPTs
    raw_rpts = session.get_flow_data(context, constants.SESSION_DATA_RAW_RPTS)
    amount = 0
    for rpt in raw_rpts:
        amount += rpt['payment_data']['total_amount']
    amount = round(amount * 100)
            
    # executing assertions
    utils.assert_show_message(response['pull'] == False, f"The payment option must be not defined for pull payments.")
    utils.assert_show_message(int(payment_option['amount']) == amount, f"The total amount calculated from all RPTs in multibeneficiary cart is not equals to the one defined in GPD payment position. GPD's: [{int(payment_option['amount'])}], RPT's: [{amount}]") 
    utils.assert_show_message(payment_option['notificationFee'] == 0, f"The notification fee in the payment position defined for GPD must be always 0.") 
    utils.assert_show_message(payment_option['isPartialPayment'] == False, f"The payment option must be not defined as partial payment.") 


# ==============================================  

@then('the response contains the transfers correctly generated from all RPTs')
def check_paymentposition_transfers_for_multibeneficiary(context):

    # retrieve response information related to executed request
    response = session.get_flow_data(context, constants.SESSION_DATA_RES_BODY)
    payment_options = utils.get_nested_field(response, "paymentOption")
    transfers_from_po = payment_options[0]['transfer']

    # retrieve transfers from RPTs in order to execute checks on data
    raw_rpts = session.get_flow_data(context, constants.SESSION_DATA_RAW_RPTS)
    transfers_from_rpt = []
    for rpt in raw_rpts:
        for transfer in rpt['payment_data']['transfers']:
            transfers_from_rpt.append(transfer)

    # executing assertions
    utils.assert_show_message(len(transfers_from_po) == len(transfers_from_rpt), f"There are not the same amount of transfers. GPD's: [{len(transfers_from_po)}], RPT's: [{len(transfers_from_rpt)}]")
    for transfer_index in range(len(transfers_from_po)):
        transfer_from_po = transfers_from_po[transfer_index]
        # using this filter because it cannot be used a filter on IUV for multibeneficiary
        transfer_from_rpt = next((transfer for transfer in transfers_from_rpt if transfer['transfer_note'] == transfer_from_po['remittanceInformation']), None)
        utils.assert_show_message(transfer_from_rpt is not None, f"It is not possible to find a transfer in RPT cart with transfer note [{transfer_from_po['remittanceInformation']}]")
        utils.assert_show_message(transfer_from_po['status'] == "T_UNREPORTED", f"The status of the transfer {transfer_index} must be equals to [T_UNREPORTED]. Current status: [{transfer_from_po['status']}]")
        utils.assert_show_message('transferMetadata' in transfer_from_po and len(transfer_from_po['transferMetadata']) > 0, f"There are not transfer metadata in transfer {transfer_index} but at least one is required.")
        utils.assert_show_message('iban' in transfer_from_po, f"There is not IBAN definition in transfer {transfer_index} but RPT transfer require it.")    
        utils.assert_show_message(transfer_from_po['iban'] == transfer_from_rpt['creditor_iban'], f"The IBAN defined in transfer {transfer_index} is not equals to the one defined in RPT. GPD's: [{transfer_from_po['iban']}], RPT's: [{transfer_from_rpt['creditor_iban']}]")
        utils.assert_show_message(int(transfer_from_po['amount']) == round(transfer_from_rpt['amount'] * 100), f"The amount of the transfer {transfer_index} must be equals to the same defined in the payment position. GPD's: [{int(transfer_from_po['amount'])}], RPT's: [{round(transfer_from_rpt['amount'])}]")
    