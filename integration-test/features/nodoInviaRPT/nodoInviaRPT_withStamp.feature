Feature: User pays a single payment with stamp via nodoInviaRPT

  Background:
    Given systems up
    And a new session

  # ===============================================================================================
  # ===============================================================================================

  @runnable @nodo_invia_rpt @happy_path
  Scenario: User pays a single payment with no simple transfer and one stamp on nodoInviaRPT
    Given a single RPT of type BBT with 1 transfers of which 1 are stamps
    When the execution of "Send a nodoInviaRPT request" was successful
    Then the execution of "Execute redirect and complete payment from NodoInviaRPT" was successful

  # ===============================================================================================
  # ===============================================================================================

  @runnable @nodo_invia_rpt @happy_path
  Scenario: User pays a single payment with one simple transfer and one stamp on nodoInviaRPT
    Given a single RPT of type BBT with 2 transfers of which 1 are stamps
    When the execution of "Send a nodoInviaRPT request" was successful
    Then the execution of "Execute redirect and complete payment from NodoInviaRPT" was successful

  # ===============================================================================================
  # ===============================================================================================

  @runnable @nodo_invia_rpt @happy_path
  Scenario: User pays a single payment with two simple transfer and one stamp on nodoInviaRPT
    Given a single RPT of type BBT with 3 transfers of which 1 are stamps
    When the execution of "Send a nodoInviaRPT request" was successful
    Then the execution of "Execute redirect and complete payment from NodoInviaRPT" was successful

  # ===============================================================================================
  # ===============================================================================================

  @runnable @nodo_invia_rpt @happy_path
  Scenario: User pays a single payment with two simple transfer and two stamp on nodoInviaRPT
    Given a single RPT of type BBT with 4 transfers of which 2 are stamps
    When the execution of "Send a nodoInviaRPT request" was successful
    Then the execution of "Execute redirect and complete payment from NodoInviaRPT" was successful


  # ===============================================================================================
  # ===============================================================================================

  @runnable @nodo_invia_rpt @happy_path
  Scenario: User pays a single payment as PO type with no simple transfer and one stamp on nodoInviaRPT
    Given a single RPT of type PO with 1 transfers of which 1 are stamps
    And a valid nodoInviaRPT request
    When the user sends a nodoInviaRPT action
    Then the user receives the HTTP status code 200 
    And the response contains the field esito with value OK
    And the response contains the old WISP URL

  # ===============================================================================================
  # ===============================================================================================

  @runnable @nodo_invia_rpt @unhappy_path
  Scenario: User pays a single payment as PO type with one simple transfer and one stamp on nodoInviaRPT
    Given a single RPT of type PO with 2 transfers of which 1 are stamps
    And a valid nodoInviaRPT request
    When the user sends a nodoInviaRPT action
    Then the user receives the HTTP status code 200 
    And the response contains the field esito with value KO
    And the response contains the field faultCode with value PPT_SEMANTICA