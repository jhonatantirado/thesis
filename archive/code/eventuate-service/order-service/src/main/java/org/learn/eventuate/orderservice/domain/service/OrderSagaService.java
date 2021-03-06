package org.learn.eventuate.orderservice.domain.service;

import io.eventuate.AggregateRepository;
import io.eventuate.EntityWithIdAndVersion;
import org.learn.eventuate.coreapi.InvoiceInfo;
import org.learn.eventuate.coreapi.OrderSagaInfo;
import org.learn.eventuate.coreapi.ParticipantFailureInfo;
import org.learn.eventuate.coreapi.ProductInfo;
import org.learn.eventuate.coreapi.ShipmentInfo;
import org.learn.eventuate.orderservice.command.saga.CompleteOrderSagaCommand;
import org.learn.eventuate.orderservice.command.saga.InitSagaCompensationCommand;
import org.learn.eventuate.orderservice.command.saga.InvoiceCompensatedCommand;
import org.learn.eventuate.orderservice.command.saga.OrderSagaCommand;
import org.learn.eventuate.orderservice.command.saga.ProcessInvoiceCommand;
import org.learn.eventuate.orderservice.command.saga.ProcessInvoiceFailureCommand;
import org.learn.eventuate.orderservice.command.saga.ProcessShipmentCommand;
import org.learn.eventuate.orderservice.command.saga.ProcessShipmentFailureCommand;
import org.learn.eventuate.orderservice.command.saga.ShipmentCompensatedCommand;
import org.learn.eventuate.orderservice.command.saga.StartOrderSagaCommand;
import org.learn.eventuate.orderservice.config.OrderServiceProperties;
import org.learn.eventuate.orderservice.domain.event.CompensateSagaEvent;
import org.learn.eventuate.orderservice.saga.OrderSagaAggregate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.concurrent.CompletableFuture;

@Component
public class OrderSagaService {

    private static final Logger log = LoggerFactory.getLogger(OrderSagaService.class);

    private static final String REQUEST = "/api/request";
    private static final String COMPENSATION = "/api/compensate";
    private static final String NOT_AVAILABLE = "N/A";

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private OrderServiceProperties properties;

    @Autowired
    private AggregateRepository<OrderSagaAggregate, OrderSagaCommand> aggregateRepository;


    public CompletableFuture<EntityWithIdAndVersion<OrderSagaAggregate>> startSaga(String orderId, ProductInfo productInfo) {
        return aggregateRepository.save(new StartOrderSagaCommand(orderId, productInfo));
    }

    public CompletableFuture<EntityWithIdAndVersion<OrderSagaAggregate>> processShipment(ShipmentInfo shipmentInfo) {
        return aggregateRepository.update(shipmentInfo.getSagaId(), new ProcessShipmentCommand(shipmentInfo));
    }

    public CompletableFuture<EntityWithIdAndVersion<OrderSagaAggregate>> processInvoice(InvoiceInfo invoiceInfo) {
        return aggregateRepository.update(invoiceInfo.getSagaId(), new ProcessInvoiceCommand(invoiceInfo));
    }

    public CompletableFuture<EntityWithIdAndVersion<OrderSagaAggregate>> processShipmentFailure(ParticipantFailureInfo failureInfo) {
        return aggregateRepository.update(failureInfo.getSagaId(), new ProcessShipmentFailureCommand(failureInfo));
    }

    public CompletableFuture<EntityWithIdAndVersion<OrderSagaAggregate>> processInvoiceFailure(ParticipantFailureInfo failureInfo) {
        return aggregateRepository.update(failureInfo.getSagaId(), new ProcessInvoiceFailureCommand(failureInfo));
    }

    public CompletableFuture<EntityWithIdAndVersion<OrderSagaAggregate>> completeSaga(String sagaId) {
        return aggregateRepository.update(sagaId, new CompleteOrderSagaCommand());
    }

    public void requestShipment(String sagaId, ProductInfo productInfo) {
        final String url = properties.getShipmentUrl() + REQUEST;
        log.info("posting shipment request for saga " + sagaId + " to " + url);

        OrderSagaInfo orderSagaInfo = new OrderSagaInfo(sagaId, productInfo);
        String result = restTemplate.postForObject(url, orderSagaInfo, String.class);
        log.info(result);
    }

    public void requestInvoice(String sagaId, ProductInfo productInfo) {
        final String url = properties.getInvoiceUrl() + REQUEST;
        log.info("posting invoice request for saga " + sagaId + " to " + url);

        OrderSagaInfo orderSagaInfo = new OrderSagaInfo(sagaId, productInfo);
        String result = restTemplate.postForObject(url, orderSagaInfo, String.class);
        log.info(result);
    }

    public CompletableFuture<EntityWithIdAndVersion<OrderSagaAggregate>> initSagaCompensation(String sagaId, String cause) {
        return aggregateRepository.update(sagaId, new InitSagaCompensationCommand(sagaId, cause));
    }

    public void compensateSaga(String sagaId, CompensateSagaEvent compensationEvent) {
        compensateShipment(sagaId, compensationEvent);
        compensateInvoice(sagaId, compensationEvent);
    }

    private void compensateShipment(String sagaId, CompensateSagaEvent compensationEvent) {
        if (!compensationEvent.getShipmentId().equals(NOT_AVAILABLE)) {
            sendShipmentCompensationRequest(sagaId, compensationEvent);
        } else {
            notifyShipmentCompensated(sagaId);
        }
    }

    private void compensateInvoice(String sagaId, CompensateSagaEvent compensationEvent) {
        if (!compensationEvent.getInvoiceId().equals(NOT_AVAILABLE)) {
            sendInvoiceCompensationRequest(sagaId, compensationEvent);
        } else {
            notifyInvoiceCompensated(sagaId);
        }
    }

    private void sendShipmentCompensationRequest(String sagaId, CompensateSagaEvent compensationEvent) {
        final String url = properties.getShipmentUrl() + COMPENSATION;
        log.info("posting shipment compensation request for saga " + sagaId + " to " + url);

        ParticipantFailureInfo failureInfo = new ParticipantFailureInfo(sagaId,
                compensationEvent.getShipmentId(), compensationEvent.getCause());
        //possibly handle compensation failure
        String result = restTemplate.postForObject(url, failureInfo, String.class);
        log.info(result);
    }

    private void sendInvoiceCompensationRequest(String sagaId, CompensateSagaEvent compensationEvent) {
        final String url = properties.getInvoiceUrl() + COMPENSATION;
        log.info("posting invoice compensation request for saga " + sagaId + " to " + url);

        ParticipantFailureInfo failureInfo = new ParticipantFailureInfo(sagaId,
                compensationEvent.getInvoiceId(), compensationEvent.getCause());

        String result = restTemplate.postForObject(url, failureInfo, String.class);
        log.info(result);
    }

    public CompletableFuture<EntityWithIdAndVersion<OrderSagaAggregate>> notifyShipmentCompensated(String sagaId) {
        return aggregateRepository.update(sagaId, new ShipmentCompensatedCommand());
    }

    public CompletableFuture<EntityWithIdAndVersion<OrderSagaAggregate>> notifyInvoiceCompensated(String sagaId) {
        return aggregateRepository.update(sagaId, new InvoiceCompensatedCommand());
    }
}
