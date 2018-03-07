package no.systema.altinn.integration;

import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.builder.ReflectionToStringBuilder;
import org.apache.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.jakewharton.fliptables.FlipTableConverters;

import de.otto.edison.hal.Link;
import no.systema.altinn.entities.ApiKey;
import no.systema.altinn.entities.AttachmentHalRepresentation;
import no.systema.altinn.entities.MessagesHalRepresentation;
import no.systema.altinn.entities.PrettyPrintAttachments;
import no.systema.altinn.entities.PrettyPrintMessages;
import no.systema.altinn.entities.ServiceCode;
import no.systema.altinn.entities.ServiceEdition;
import no.systema.altinn.entities.ServiceOwner;
import no.systema.jservices.common.dao.FirmaltDao;
import no.systema.jservices.common.dao.services.FirmaltDaoService;
import no.systema.jservices.common.util.DateTimeManager;

/**
 * The responsible service manager for accessing resources inside www.altinn.no <br>
 * 
 * Implementing part of actions found here: https://www.altinn.no/api/Help <br>
 * 
 * 
 * @author Fredrik Möller
 * @date 2018-01
 *
 */
@EnableScheduling
@Service("actionsservicemanager")
public class ActionsServiceManager {
	private static Logger logger = Logger.getLogger(ActionsServiceManager.class.getName());
	DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("yyyyMMdd"); //as defined in Firmalt
	DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HHmmss");  //as defined in Firmalt.
	
	@Autowired
	private Authorization authorization;
	
	@Autowired
	private FirmaltDaoService firmaltDaoService;

	@Bean
	public RestTemplate restTemplate() {
		return new RestTemplate();
	}	
	
	@Autowired
	private RestTemplate restTemplate;
	
	/**
	 * Get all messages for orgnr
	 * 
	 * @see {@link ActionsUriBuilder}
	 * @param forceDetails, convenience for troubleshooting, typical use is false.
	 * @return List<PrettyPrintMessages>
	 */
	public List<PrettyPrintMessages> getMessages(boolean forceDetails) {
		final List<PrettyPrintMessages> result = new ArrayList<PrettyPrintMessages>();
		List<FirmaltDao> firmaltDaoList =firmaltDaoService.get();
		firmaltDaoList.forEach(firmalt -> {
			URI uri = ActionsUriBuilder.messages(firmalt.getAihost(), firmalt.getAiorg());
			if (forceDetails) {
				List<MessagesHalRepresentation> messages = getMessages(uri, firmalt);

				messages.forEach((message) -> {
					String self = message.getLinks().getLinksBy("self").get(0).getHref();
					MessagesHalRepresentation halMessage = getMessage(URI.create(self),firmalt);
					PrettyPrintMessages log = new PrettyPrintMessages(firmalt.getAiorg(), LocalDateTime.now().toString(),halMessage.getCreatedDate().toString(), 
							halMessage.getSubject(), halMessage.getServiceOwner(), halMessage.getServiceCode(), halMessage.getServiceEdition() );

					result.add(log);
				});
	
			} else {
				List<MessagesHalRepresentation> messages = getMessages(uri, firmalt);

				messages.forEach((message) -> {
					PrettyPrintMessages log = new PrettyPrintMessages(firmalt.getAiorg(), LocalDateTime.now().toString(),message.getCreatedDate().toString(), 
							message.getSubject(), message.getServiceOwner(), message.getServiceCode(), message.getServiceEdition() );

					result.add(log);
				});				
			
			}
		});

		return result;

	}
	
	/**
	 * Get all message for orgnr and specific {@link ServiceOwner}
	 * 
	 * @see {@link ActionsUriBuilder}
	 * @param orgnr
	 * @param serviceOwner
	 * @return List<MessagesHalRepresentation>
	 */
	public List<MessagesHalRepresentation> getMessages(ServiceOwner serviceOwner) {
		final List<MessagesHalRepresentation> result = new ArrayList<MessagesHalRepresentation>();
		final List<FirmaltDao> firmaltDaoList =firmaltDaoService.get();
		firmaltDaoList.forEach(firmalt -> {	
			URI uri = ActionsUriBuilder.messages(firmalt.getAihost(),  firmalt.getAiorg(),serviceOwner);
			result.addAll(getMessages(uri,firmalt));
		});
		
		return result;
		
	}	
	
	/**
	 * Get all message for orgnr and specific {@link ServiceOwner}, {@link ServiceOwner}, {@link ServiceEdition}
	 * 
	 * @see {@link ActionsUriBuilder}
	 * @param orgnr
	 * @param serviceOwner
	 * @param serviceCode
	 * @param serviceEdition
	 * @return List<MessagesHalRepresentation>
	 */
	public List<MessagesHalRepresentation> getMessages(ServiceOwner serviceOwner, ServiceCode serviceCode, ServiceEdition serviceEdition, FirmaltDao firmalt) {
		final List<MessagesHalRepresentation> result = new ArrayList<MessagesHalRepresentation>();
		URI uri = ActionsUriBuilder.messages(firmalt.getAihost(),  firmalt.getAiorg(),serviceOwner,serviceCode, serviceEdition);
		result.addAll(getMessages(uri,firmalt));
		
		return result;
		
	}	
	
	private List<MessagesHalRepresentation> getMessages(ServiceOwner serviceOwner, ServiceCode serviceCode, ServiceEdition serviceEdition, LocalDateTime createdDate, FirmaltDao firmalt) {
		logger.info("About to get message greater than "+createdDate+ " for orgnr:"+firmalt.getAiorg());
		final List<MessagesHalRepresentation> result = new ArrayList<MessagesHalRepresentation>();
		URI uri = ActionsUriBuilder.messages(firmalt.getAihost(), firmalt.getAiorg(), serviceOwner, serviceCode, serviceEdition, createdDate);
		result.addAll(getMessages(uri, firmalt));
		
		return result;
	
	}	
	
	/**
	 * Retrieves all attachment in Melding: Dagsoppgjor, for today <br>
	 * and stores as defined in {@linkplain FirmaltDao}.aipath
	 * 
	 * @param forceAll removes filter day, convenience for troubleshooting manually, typical use is false.
	 * @return List of fileNames
	 */
	public List<PrettyPrintAttachments> putDagsobjorAttachmentsToPath(boolean forceAll, LocalDateTime fraDato) {
		List<PrettyPrintAttachments> logRecords = new ArrayList<PrettyPrintAttachments>();
		final List<FirmaltDao> firmaltDaoList =firmaltDaoService.get();
		firmaltDaoList.forEach(firmalt -> {
			if (fraDato != null || forceAll) {
				List<MessagesHalRepresentation> dagsobjors = null;
				if (fraDato != null) {
					logger.info("Orgnr:"+firmalt.getAiorg()+ ", downloading fraDato-filtered messages from "+fraDato+", from Skatteeten on Dagsoppgjor");
					logger.info("fraDato="+fraDato);
					dagsobjors = getMessages(ServiceOwner.Skatteetaten, ServiceCode.Dagsobjor, ServiceEdition.Dagsobjor, fraDato, firmalt);

					/** 2018_03-02
					 * Det har også blitt oppdaget en feil i oppsettet for enkelttjeneste for den nye ordningen for dagsoppgjør. Denne feilen berører kun de som ønsker å tildele enkeltpersoner enkelttjenester i Altinn. 
					* For å løse dette søk opp 4125/150602 "Brev til etterskuddspliktige" og velg denne. I tillegg er det laget en ny enkelttjeneste som er riktig 5012/171208 "Elektronisk kontoutskrift tollkreditt og dagsoppgjør" som vil være gyldig i løpet av 3-4 uker. Tildel denne samtidig og den vil automatisk bli tatt i bruk når den nye tjenesten er klar.
					* Har en rolle som "Regnskapsmedarbeider" vil en uansett ha tilgang til å laste ned PDF- og e2b-fil fra Altinn og vil ikke bli berørt av endringen.
					 */
					//TODO: To be removed when 5012/171208 is working. Planned to work  2018-03/2018-04
					List<MessagesHalRepresentation> dagsobjorsFIX = getMessages(ServiceOwner.Skatteetaten,ServiceCode.DagsobjorFIX, ServiceEdition.DagsobjorFIX, fraDato,firmalt);
					logger.info(dagsobjorsFIX.size() +" messages found on ServiceOwner="+ServiceOwner.Skatteetaten.getCode()+", ServiceCode="+ServiceCode.DagsobjorFIX.getCode()+", ServiceEdition="+ServiceEdition.DagsobjorFIX.getCode());
					dagsobjors.addAll(dagsobjorsFIX);					
					
				
				} else {  //forceAll
					logger.info("Orgnr:"+firmalt.getAiorg()+ ", downloading all messages from Skatteeten on Dagsoppgjor");
					dagsobjors = getMessages(ServiceOwner.Skatteetaten, ServiceCode.Dagsobjor, ServiceEdition.Dagsobjor, firmalt);

					/** 2018_03-02
					 * Det har også blitt oppdaget en feil i oppsettet for enkelttjeneste for den nye ordningen for dagsoppgjør. Denne feilen berører kun de som ønsker å tildele enkeltpersoner enkelttjenester i Altinn. 
					* For å løse dette søk opp 4125/150602 "Brev til etterskuddspliktige" og velg denne. I tillegg er det laget en ny enkelttjeneste som er riktig 5012/171208 "Elektronisk kontoutskrift tollkreditt og dagsoppgjør" som vil være gyldig i løpet av 3-4 uker. Tildel denne samtidig og den vil automatisk bli tatt i bruk når den nye tjenesten er klar.
					* Har en rolle som "Regnskapsmedarbeider" vil en uansett ha tilgang til å laste ned PDF- og e2b-fil fra Altinn og vil ikke bli berørt av endringen.
					 */
					//TODO: To be removed when 5012/171208 is working. Planned to work  2018-03/2018-04
					List<MessagesHalRepresentation> dagsobjorsFIX = getMessages(ServiceOwner.Skatteetaten,ServiceCode.DagsobjorFIX, ServiceEdition.DagsobjorFIX, firmalt);
					logger.info(dagsobjorsFIX.size() +" messages found on ServiceOwner="+ServiceOwner.Skatteetaten.getCode()+", ServiceCode="+ServiceCode.DagsobjorFIX.getCode()+", ServiceEdition="+ServiceEdition.DagsobjorFIX.getCode());
					dagsobjors.addAll(dagsobjorsFIX);						
				
				}

				
				dagsobjors.forEach((message) -> {
					logRecords.addAll(getAttachments(message, firmalt));
				});
				
				if (!dagsobjors.isEmpty()) {
					updateDownloadDato(firmalt);
				}
				logger.info("Orgnr:"+firmalt.getAiorg()+ ", " +dagsobjors.size()+" dagsoppgjor downloaded, with "+logRecords.size()+" attachments.");
			} else {
				logger.info("Orgnr:"+firmalt.getAiorg()+ ", downloading if not downloaded today.");
				if (!isDownloadedToday(firmalt)) {
					logRecords.addAll(getDagsoppgjor(firmalt));
				}
				logger.info("Orgnr:"+firmalt.getAiorg()+ " with "+logRecords.size()+" attachments.");

			}
		});

		logger.info("putDagsobjorAttachmentsToPath executed, with forceAll="+forceAll+", fraDato="+fraDato);
		logger.info(FlipTableConverters.fromIterable(logRecords, PrettyPrintAttachments.class));
		
		return logRecords;

	}


	@Scheduled(cron="${altinn.file.download.cron.pattern}")
	private List<PrettyPrintAttachments> putDagsobjorAttachmentsToPath() {
		List<PrettyPrintAttachments> logRecords = new ArrayList<PrettyPrintAttachments>();
		final List<FirmaltDao> firmaltDaoList =firmaltDaoService.get();
		firmaltDaoList.forEach(firmalt -> {
			logger.info("::Scheduled:: :Get messages for orgnnr:"+firmalt.getAiorg());
			if (!isDownloadedToday(firmalt)) {
				logRecords.addAll(getDagsoppgjor(firmalt));	

				logger.info("::Scheduled:: download of Dagsoppgjors attachments is executed.");
				logger.info(FlipTableConverters.fromIterable(logRecords, PrettyPrintAttachments.class));
			} else {
				logger.info("::Scheduled:: orgnnr:"+firmalt.getAiorg() +" Already downloaded today.");
				logger.info("::Scheduled::Actual values in FIRMALT="+ReflectionToStringBuilder.toString(firmalt));
			}
			
		});
		
		return logRecords;

	}	
	
	private List<PrettyPrintAttachments> getDagsoppgjor(FirmaltDao firmalt) {
		List<PrettyPrintAttachments> logRecords = new ArrayList<PrettyPrintAttachments>();
		LocalDateTime createdDate = getFromCreatedDate(firmalt);
		List<MessagesHalRepresentation> dagsobjors = getMessages(ServiceOwner.Skatteetaten,ServiceCode.Dagsobjor, ServiceEdition.Dagsobjor, createdDate,firmalt);
		logger.info(dagsobjors.size() +" messages found on ServiceOwner="+ServiceOwner.Skatteetaten.getCode()+", ServiceCode="+ServiceCode.Dagsobjor.getCode()+", ServiceEdition="+ServiceEdition.Dagsobjor.getCode());
		/** 2018_03-02
		 * Det har også blitt oppdaget en feil i oppsettet for enkelttjeneste for den nye ordningen for dagsoppgjør. Denne feilen berører kun de som ønsker å tildele enkeltpersoner enkelttjenester i Altinn. 
		* For å løse dette søk opp 4125/150602 "Brev til etterskuddspliktige" og velg denne. I tillegg er det laget en ny enkelttjeneste som er riktig 5012/171208 "Elektronisk kontoutskrift tollkreditt og dagsoppgjør" som vil være gyldig i løpet av 3-4 uker. Tildel denne samtidig og den vil automatisk bli tatt i bruk når den nye tjenesten er klar.
		* Har en rolle som "Regnskapsmedarbeider" vil en uansett ha tilgang til å laste ned PDF- og e2b-fil fra Altinn og vil ikke bli berørt av endringen.
		 */
		//TODO: To be removed when 5012/171208 is working. Planned to work  2018-03/2018-04
		List<MessagesHalRepresentation> dagsobjorsFIX = getMessages(ServiceOwner.Skatteetaten,ServiceCode.DagsobjorFIX, ServiceEdition.DagsobjorFIX, createdDate,firmalt);
		logger.info(dagsobjorsFIX.size() +" messages found on ServiceOwner="+ServiceOwner.Skatteetaten.getCode()+", ServiceCode="+ServiceCode.DagsobjorFIX.getCode()+", ServiceEdition="+ServiceEdition.DagsobjorFIX.getCode());
		dagsobjors.addAll(dagsobjorsFIX);
		
		dagsobjors.forEach((message) -> {
			logRecords.addAll(getAttachments(message, firmalt));
		});	
		
		if (!dagsobjors.isEmpty()) {
			updateDownloadDato(firmalt);
		}
		logger.info("Orgnr:"+firmalt.getAiorg()+ ", " +dagsobjors.size()+" Dagsoppgjor downloaded, with "+logRecords.size()+" attachments.");
	
		return logRecords;
		
	}
	
	private LocalDateTime getFromCreatedDate(FirmaltDao firmalt) {
		int aidato;
		String aidatoString;
		String aitidString;
		if (firmalt.getAidato() == 0) {
			throw new RuntimeException("FIRMALT.aidato not set!");
		} else {
			aidato = firmalt.getAidato();
			aidatoString = String.valueOf(aidato);
		}

		aitidString = String.format("%06d", firmalt.getAitid());  //pad up to 6 char, value can be between e.g. 3 to 122333	eq. 00:00:03 and 12:23:33
		LocalDate fromDate = LocalDate.parse(aidatoString, dateFormatter);
		LocalTime fromTime = LocalTime.parse(aitidString, timeFormatter);
		
		return  LocalDateTime.of(fromDate, fromTime);

	}
		
	private void updateDownloadDato(FirmaltDao firmalt) {
		LocalDateTime now = LocalDateTime.now();
		String nowDate = now.format(dateFormatter);
		int aidato = Integer.valueOf(nowDate);
		
		String nowTime = now.format(timeFormatter);
		int aitid = Integer.valueOf(nowTime);		
		
		firmalt.setAidato(aidato);
		firmalt.setAitid(aitid);
		firmaltDaoService.update(firmalt);
		
	}

	private boolean isDownloadedToday(FirmaltDao firmalt) {
		//Sanity check
		if (firmalt.getAidato() == 0) {
			throw new RuntimeException("FIRMALT.aidato not set!");
		}
		
		if (firmalt.getAidato() < getNow() ) {
			logger.info("Files for orgnr:"+firmalt.getAiorg()+" not downloaded today. About to executed download...");
			return false;
		} else {
			logger.info("Files for orgnr:"+firmalt.getAiorg()+" downloaded today.");
			return true;
		}

	}	
	
	private int getNow() {
		DateTimeManager dtm = new DateTimeManager();
		String nowString = dtm.getCurrentDate_ISO();
		int now = Integer.valueOf(nowString);
		
		return now;
	}
	
	/*
	 * Get all attachments in message, e.i. PDF and XML
	 */
	private List<PrettyPrintAttachments> getAttachments(MessagesHalRepresentation message, FirmaltDao firmalt) {
		List<PrettyPrintAttachments> logRecords = new ArrayList<PrettyPrintAttachments>();
		String self = message.getLinks().getLinksBy("self").get(0).getHref();
		
		URI uri = URI.create(self);
		//Get specific message
		MessagesHalRepresentation halMessage = getMessage(uri, firmalt);
		
		List<Link> attachmentsLink =halMessage.getLinks().getLinksBy("attachment");
		
		attachmentsLink.forEach((attLink) -> {
//			logger.debug("attLink="+attLink);
//			logger.debug("attLink, rtsb.toString="+ReflectionToStringBuilder.toString(attLink));

			URI attUri = URI.create(attLink.getHref());

//			AttachmentHalRepresentation attHalRep = getAttachmentHalRepresentation(attUri, firmalt);	
//			logger.debug("attHalRep="+attHalRep);
//			logger.debug("attHalRep rtsb.tos="+ReflectionToStringBuilder.toString(attHalRep));

			//Prefix Altinn-name with created_date
			StringBuilder writeFile;
			if (attLink.getName().endsWith(".pdf") || attLink.getName().endsWith(".xml")) { 
				writeFile = new StringBuilder(halMessage.getCreatedDate().toString()).append("-").append(attLink.getName());
			} else {
				/*2018-03: Could be lead to problem in future if xml name is changed.
				 * be aware....
				 */
				if (attLink.getName().startsWith("Et"))  {
					writeFile = new StringBuilder(halMessage.getCreatedDate().toString()).append("-").append(attLink.getName()).append(".xml");
				} else {
					writeFile = new StringBuilder(halMessage.getCreatedDate().toString()).append("-").append(attLink.getName()).append(".pdf");
				}
			}
			getAttachment(attUri, writeFile.toString(), firmalt);
			PrettyPrintAttachments log = new PrettyPrintAttachments(firmalt.getAiorg(), LocalDateTime.now().toString(),halMessage.getCreatedDate().toString(), writeFile.toString(), halMessage.getServiceOwner() );
			logRecords.add(log);
			
		});
		
		return logRecords;

	}	

	/*
	 * FirmaltDao as param is her due to late fix in model. (logically not really neede.)
	 */
	private List<MessagesHalRepresentation> getMessages(URI uri, FirmaltDao firmaltDao){
		HttpEntity<ApiKey> entityHeadersOnly = authorization.getHttpEntity(firmaltDao);
		ResponseEntity<String> responseEntity = null;
		
		try {

			responseEntity = restTemplate.exchange(uri, HttpMethod.GET, entityHeadersOnly, String.class); 

			if (responseEntity.getStatusCode() != HttpStatus.OK) {
				logger.error("Error in getMessage for " + uri);
				throw new RuntimeException(responseEntity.getStatusCode().toString());
			}
			logger.debug("responseEntity.getBody"+responseEntity.getBody());
	
	        return HalHelper.getMessages(responseEntity.getBody());
	        
		} catch (Exception e) {
			String errMessage = String.format(" request failed: %s", e.getLocalizedMessage());
			logger.warn(errMessage, e);
			throw new RuntimeException(errMessage);
		}
		
	}	
	
	/*
	 * FirmaltDao as param is her due to late fix in model. (logically not really needed.)
	 */
	private MessagesHalRepresentation getMessage(URI uri, FirmaltDao firmaltDao){
		HttpEntity<ApiKey> entityHeadersOnly = authorization.getHttpEntity(firmaltDao);
		ResponseEntity<String> responseEntity = null;
		
		try {

			responseEntity = restTemplate.exchange(uri, HttpMethod.GET, entityHeadersOnly, String.class); 

			if (responseEntity.getStatusCode() != HttpStatus.OK) {
				logger.error("Error in getMessage for " + uri);
				throw new RuntimeException(responseEntity.getStatusCode().toString());
			}
			logger.debug("responseEntity.getBody"+responseEntity.getBody());
	
	        return HalHelper.getMessage(responseEntity.getBody());
	        
		} catch (Exception e) {
			String errMessage = String.format(" request failed: %s", e.getLocalizedMessage());
			logger.warn(errMessage, e);
			throw new RuntimeException(errMessage);
		}
		
	}	

	/*
	 * FirmaltDao as param is her due to late fix in model. (logically not really needed.)
	 */
	private void getAttachment(URI uri, String writeFile, FirmaltDao firmaltDao) {
		HttpEntity<ApiKey> entityHeadersOnly = authorization.getHttpEntityFileDownload(firmaltDao);
		ResponseEntity<byte[]> responseEntity = null;

		try {
			logger.debug("getAttachment, uri=" + uri);

			responseEntity = restTemplate.exchange(uri.toString(), HttpMethod.GET, entityHeadersOnly, byte[].class, "1");

			if (responseEntity.getStatusCode() != HttpStatus.OK) {
				logger.error("Error in getAttachment for " + uri);
				throw new RuntimeException(responseEntity.getStatusCode().toString());
			} else {
				logger.debug("getAttachment::responseEntity.getBody"+responseEntity.getBody());
				writeToFile(writeFile, responseEntity, firmaltDao);

			}

		} catch (Exception e) {
			String errMessage = String.format(" request failed: %s", e.getLocalizedMessage());
			logger.warn(errMessage, e);
			throw new RuntimeException(errMessage);
		}

	}

	private AttachmentHalRepresentation getAttachmentHalRepresentation(URI uri,  FirmaltDao firmaltDao) {
		HttpEntity<ApiKey> entityHeadersOnly = authorization.getHttpEntity(firmaltDao);
//		HttpEntity<ApiKey> entityHeadersOnly = authorization.getHttpEntityFileDownload(firmaltDao);	
//		ResponseEntity<byte[]> responseEntity = null;
		ResponseEntity<String> responseEntity = null;
		
		try {

			responseEntity = restTemplate.exchange(uri, HttpMethod.GET, entityHeadersOnly, String.class); 
//			responseEntity = restTemplate.exchange(uri, HttpMethod.GET, entityHeadersOnly, byte[].class); 
			
			if (responseEntity.getStatusCode() != HttpStatus.OK) {
				logger.error("Error in getMessage for " + uri);
				throw new RuntimeException(responseEntity.getStatusCode().toString());
			}
			logger.debug("getAttachmentHalRepresentation(String.class): responseEntity.getBody.toString"+responseEntity.getBody().toString());
	
//	        return HalHelper.getAttachment(responseEntity.getBody());
			logger.info("Returning null");
			return null;
			
	        
		} catch (Exception e) {
			String errMessage = String.format(" request failed: %s", e.getLocalizedMessage());
			logger.warn(errMessage, e);
			throw new RuntimeException(errMessage);
		}
	}	
	
	
	/*
	 * For test
	 * FileOutputStream fos = new FileOutputStream("/usr/local/Cellar/tomcat/8.0.33/libexec/webapps/altinn-proxy/WEB-INF/resources/files/" + writeFile);
	 */
	private void writeToFile(String writeFile, ResponseEntity<byte[]> responseEntity, FirmaltDao firmaltDao) throws FileNotFoundException, IOException {
		ByteArrayInputStream bis = new ByteArrayInputStream(responseEntity.getBody());
		FileOutputStream fos = new FileOutputStream(firmaltDao.getAipath() + writeFile);
		byte[] buffer = new byte[1024];
		int len = 0;
		while ((len = bis.read(buffer)) > 0) {
			fos.write(buffer, 0, len);
		}

		fos.close();
		bis.close();

		logger.info("File: " + firmaltDao.getAipath() + writeFile + " saved on disk.");

	}
	
}
