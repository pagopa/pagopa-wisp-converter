const {getCurrentDate, getCurrentDateTime, makeNumericalString} = require("../../lib/util");

let values = {
        creditorInstitutionBroker: "15376371009",
        creditorInstitution: "77777777777",
        station: "15376371009_48",
        psp: "AGID_01",
        pspBroker: "97735020584",
        channel: "97735020584_03",
        payerFiscalCode: "RSSMRA70A01H501Z",
        payerName: "Mario Rossi",
        payeeFiscalCode: "11111111117",
        payerName: "Enel",
        transfers: [
            {
                totalAmount: "16.50",
                iuv: makeNumericalString(15),
                ccp: "CCD01",
                debtorIban: "IT45R0760103200000000001016",
                debtorBic: "ARTIITM1045",
                singleTransfers: [
                    {
                        amount: "16.00",
                        fee: "0.50",
                        payerInfo: "CP1.1",
                        paymentDescription: "Marca da bollo da 16 euro",
                        taxonomy: "9/0301109AP"
                    },
                ]
            }
        ]
    }

function getNodoInviaRPT() {
    let rpt = btoa(getRPT());
    return `
        <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:ppt="http://ws.pagamenti.telematici.gov/ppthead" xmlns:ws="http://ws.pagamenti.telematici.gov/">
            <soapenv:Header>
                <ppt:intestazionePPT>
                    <identificativoIntermediarioPA>${values.creditorInstitutionBroker}</identificativoIntermediarioPA>
                    <identificativoStazioneIntermediarioPA>${values.station}</identificativoStazioneIntermediarioPA>
                    <identificativoDominio>${values.creditorInstitution}</identificativoDominio>
                    <identificativoUnivocoVersamento>${values.transfers[0].iuv}</identificativoUnivocoVersamento>
                    <codiceContestoPagamento>CCD01</codiceContestoPagamento>
                </ppt:intestazionePPT>
            </soapenv:Header>
            <soapenv:Body>
                <ws:nodoInviaRPT>
                    <password>pwdpwdpwd</password>
                    <identificativoPSP>${values.psp}</identificativoPSP>
                    <identificativoIntermediarioPSP>${values.pspBroker}</identificativoIntermediarioPSP>
                    <identificativoCanale>${values.channel}</identificativoCanale>
                    <tipoFirma></tipoFirma>
                    <rpt>${rpt}</rpt>
                </ws:nodoInviaRPT>
            </soapenv:Body>
        </soapenv:Envelope>
        `;
}

function getRPT() {
    return `
           <pay_i:RPT xmlns:pay_i="http://www.digitpa.gov.it/schemas/2011/Pagamenti/" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://www.digitpa.gov.it/schemas/2011/Pagamenti/ PagInf_RPT_RT_6_2_0.xsd ">
               <pay_i:versioneOggetto>6.0</pay_i:versioneOggetto>
               <pay_i:dominio>
                   <pay_i:identificativoDominio>${values.creditorInstitution}</pay_i:identificativoDominio>
                   <pay_i:identificativoStazioneRichiedente>${values.station}</pay_i:identificativoStazioneRichiedente>
               </pay_i:dominio>
               <pay_i:identificativoMessaggioRichiesta>systemtest</pay_i:identificativoMessaggioRichiesta>
               <pay_i:dataOraMessaggioRichiesta>${getCurrentDateTime()}</pay_i:dataOraMessaggioRichiesta>
               <pay_i:autenticazioneSoggetto>CNS</pay_i:autenticazioneSoggetto>
               <pay_i:soggettoVersante>
                   <pay_i:identificativoUnivocoVersante>
                       <pay_i:tipoIdentificativoUnivoco>F</pay_i:tipoIdentificativoUnivoco>
                       <pay_i:codiceIdentificativoUnivoco>${values.payerFiscalCode}</pay_i:codiceIdentificativoUnivoco>
                   </pay_i:identificativoUnivocoVersante>
                   <pay_i:anagraficaVersante>${values.payerName}</pay_i:anagraficaVersante>
                   <pay_i:indirizzoVersante>via Roma</pay_i:indirizzoVersante>
                   <pay_i:civicoVersante>1</pay_i:civicoVersante>
                   <pay_i:capVersante>00186</pay_i:capVersante>
                   <pay_i:localitaVersante>Roma</pay_i:localitaVersante>
                   <pay_i:provinciaVersante>RM</pay_i:provinciaVersante>
                   <pay_i:nazioneVersante>IT</pay_i:nazioneVersante>
               </pay_i:soggettoVersante>
               <pay_i:soggettoPagatore>
                   <pay_i:identificativoUnivocoPagatore>
                       <pay_i:tipoIdentificativoUnivoco>F</pay_i:tipoIdentificativoUnivoco>
                       <pay_i:codiceIdentificativoUnivoco>${values.payerFiscalCode}</pay_i:codiceIdentificativoUnivoco>
                   </pay_i:identificativoUnivocoPagatore>
                   <pay_i:anagraficaPagatore>${values.payerName}</pay_i:anagraficaPagatore>
                   <pay_i:indirizzoPagatore>via Roma</pay_i:indirizzoPagatore>
                   <pay_i:civicoPagatore>1</pay_i:civicoPagatore>
                   <pay_i:capPagatore>00186</pay_i:capPagatore>
                   <pay_i:localitaPagatore>Roma</pay_i:localitaPagatore>
                   <pay_i:provinciaPagatore>RM</pay_i:provinciaPagatore>
                   <pay_i:nazionePagatore>IT</pay_i:nazionePagatore>
               </pay_i:soggettoPagatore>
               <pay_i:enteBeneficiario>
                   <pay_i:identificativoUnivocoBeneficiario>
                       <pay_i:tipoIdentificativoUnivoco>G</pay_i:tipoIdentificativoUnivoco>
                       <pay_i:codiceIdentificativoUnivoco>${values.payeeFiscalCode}</pay_i:codiceIdentificativoUnivoco>
                   </pay_i:identificativoUnivocoBeneficiario>
                   <pay_i:denominazioneBeneficiario>${values.payeeName}</pay_i:denominazioneBeneficiario>
                   <pay_i:codiceUnitOperBeneficiario>123</pay_i:codiceUnitOperBeneficiario>
                   <pay_i:denomUnitOperBeneficiario>XXX</pay_i:denomUnitOperBeneficiario>
                   <pay_i:indirizzoBeneficiario>via Napoleone</pay_i:indirizzoBeneficiario>
                   <pay_i:civicoBeneficiario>1</pay_i:civicoBeneficiario>
                   <pay_i:capBeneficiario>00123</pay_i:capBeneficiario>
                   <pay_i:localitaBeneficiario>Roma</pay_i:localitaBeneficiario>
                   <pay_i:provinciaBeneficiario>RM</pay_i:provinciaBeneficiario>
                   <pay_i:nazioneBeneficiario>IT</pay_i:nazioneBeneficiario>
               </pay_i:enteBeneficiario>
               <pay_i:datiVersamento>
                   <pay_i:dataEsecuzionePagamento>${getCurrentDate()}</pay_i:dataEsecuzionePagamento>
                   <pay_i:importoTotaleDaVersare>${values.transfers[0].totalAmount}</pay_i:importoTotaleDaVersare>
                   <pay_i:tipoVersamento>PO</pay_i:tipoVersamento>
                   <pay_i:identificativoUnivocoVersamento>${values.transfers[0].iuv}</pay_i:identificativoUnivocoVersamento>
                   <pay_i:codiceContestoPagamento>${values.transfers[0].ccp}</pay_i:codiceContestoPagamento>
                   <pay_i:ibanAddebito>${values.transfers[0].debtorIban}</pay_i:ibanAddebito>
                   <pay_i:bicAddebito>${values.transfers[0].debtorBic}</pay_i:bicAddebito>
                   <pay_i:firmaRicevuta>0</pay_i:firmaRicevuta>
                   <pay_i:datiSingoloVersamento>
                       <pay_i:importoSingoloVersamento>${values.transfers[0].singleTransfers[0].amount}</pay_i:importoSingoloVersamento>
                       <pay_i:commissioneCaricoPA>${values.transfers[0].singleTransfers[0].fee}</pay_i:commissioneCaricoPA>
                       <pay_i:credenzialiPagatore>${values.transfers[0].singleTransfers[0].payerInfo}</pay_i:credenzialiPagatore>
                       <pay_i:causaleVersamento>${values.transfers[0].singleTransfers[0].paymentDescription}</pay_i:causaleVersamento>
                       <pay_i:datiSpecificiRiscossione>${values.transfers[0].singleTransfers[0].taxonomy}</pay_i:datiSpecificiRiscossione>
                       <pay_i:datiMarcaBolloDigitale>
                           <pay_i:tipoBollo>01</pay_i:tipoBollo>
                           <pay_i:hashDocumento>wHpFSLCGZjIvNSXxqtGbxg7275t446DRTk5ZrsdUQ6E=</pay_i:hashDocumento>
                           <pay_i:provinciaResidenza>RM</pay_i:provinciaResidenza>
                       </pay_i:datiMarcaBolloDigitale>
                   </pay_i:datiSingoloVersamento>
               </pay_i:datiVersamento>
           </pay_i:RPT>
           `;
}

module.exports = {getNodoInviaRPT}