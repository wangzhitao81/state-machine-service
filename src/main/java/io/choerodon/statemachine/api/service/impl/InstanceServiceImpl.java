package io.choerodon.statemachine.api.service.impl;

import feign.*;
import feign.codec.Decoder;
import feign.codec.Encoder;
import io.choerodon.statemachine.api.dto.ExecuteResult;
import io.choerodon.statemachine.api.dto.StateMachineConfigDTO;
import io.choerodon.statemachine.api.dto.StateMachineTransfDTO;
import io.choerodon.statemachine.api.service.InstanceService;
import io.choerodon.statemachine.api.service.StateMachineConfigService;
import io.choerodon.statemachine.api.service.StateMachineTransfService;
import io.choerodon.statemachine.infra.enums.StateMachineConfigType;
import io.choerodon.statemachine.infra.factory.MachineFactory;
import io.choerodon.statemachine.infra.feign.CustomFeignClientAdaptor;
import io.choerodon.statemachine.infra.mapper.StateMachineNodeMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.netflix.feign.FeignClientsConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.statemachine.StateContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;

/**
 * @author shinan.chen
 * @date 2018/9/18
 */
@Component
@Import(FeignClientsConfiguration.class)
@Transactional(rollbackFor = Exception.class)
public class InstanceServiceImpl implements InstanceService {

    @Autowired
    private StateMachineNodeMapper nodeMapper;
    @Autowired
    private StateMachineConfigService configService;
    @Autowired
    private StateMachineTransfService transfService;
    @Autowired
    private MachineFactory machineFactory;

    private static final Logger LOGGER = LoggerFactory.getLogger(InstanceServiceImpl.class);

    private static final String METHOD_FILTER_TRANSF = "config_filter";
    private static final String METHOD_EXECUTE_CONFIG = "execute_config";
    private static final String ERROR_VALIDATORGUARD = "error.customFeignClientAdaptor.executeConfig.validatorGuard";
    private static final String EXCEPTION = "Exception:{}";


    /**
     * 自定义FeignClient客户端
     */
    private CustomFeignClientAdaptor customFeignClientAdaptor;

    @Autowired
    public InstanceServiceImpl(Client client, Decoder decoder, Encoder encoder, RequestInterceptor interceptor) {
        customFeignClientAdaptor = Feign.builder().encoder(encoder).decoder(decoder)
                .client(client)
                .contract(new Contract.Default())
                .requestInterceptor(interceptor)
                .target(Target.EmptyTarget.create(CustomFeignClientAdaptor.class));
    }

    @Override
    public ExecuteResult startInstance(Long organizationId, String serviceCode, Long stateMachineId, Long instanceId) {
        ExecuteResult executeResult;
        try {
            executeResult = machineFactory.startInstance(organizationId, serviceCode, stateMachineId, instanceId);
        } catch (Exception e) {
            LOGGER.error(EXCEPTION, e);
            executeResult = new ExecuteResult(false, null, e.getMessage());
        }
        return executeResult;
    }

    @Override
    public ExecuteResult executeTransf(Long organizationId, String serviceCode, Long stateMachineId, Long instanceId, Long currentStateId, Long transfId) {
        ExecuteResult executeResult;
        try {
            executeResult = machineFactory.executeTransf(organizationId, serviceCode, stateMachineId, instanceId, currentStateId, transfId);
        } catch (Exception e) {
            LOGGER.error(EXCEPTION, e);
            executeResult = new ExecuteResult(false, null, e.getMessage());
        }
        return executeResult;
    }

    @Override
    public List<StateMachineTransfDTO> queryListTransf(Long organizationId, String serviceCode, Long stateMachineId, Long instanceId, Long stateId) {
        List<StateMachineTransfDTO> list = transfService.queryListByStateId(organizationId, stateMachineId, stateId);
        //获取转换的条件配置
        list.forEach(stateMachineTransfDTO -> stateMachineTransfDTO.setConditions(condition(stateMachineTransfDTO.getOrganizationId(), stateMachineTransfDTO.getId())));
        //调用对应服务，根据条件校验转换，过滤掉可用的转换
        list = list == null ? Collections.emptyList() : list;
        try {
            ResponseEntity<List<StateMachineTransfDTO>> listEntity = customFeignClientAdaptor.filterTransfsByConfig(getURI(serviceCode, organizationId, METHOD_FILTER_TRANSF, instanceId, null, null, null), list);
            list = listEntity.getBody();
        } catch (Exception e) {
            LOGGER.error(EXCEPTION, e);
            list = Collections.emptyList();
        }
        return list;
    }

    @Override
    public Boolean validatorGuard(Long organizationId, String serviceCode, Long transfId, Long instanceId, StateContext<String, String> context) {
        List<StateMachineConfigDTO> configs = validator(organizationId, transfId);
        ExecuteResult executeResult;
        //调用对应服务，执行验证，返回是否成功
        try {
            ResponseEntity<ExecuteResult> executeResultEntity = customFeignClientAdaptor.executeConfig(getURI(serviceCode, organizationId, METHOD_EXECUTE_CONFIG, instanceId, null, null, StateMachineConfigType.STATUS_VALIDATOR.value()), configs);
            //返回为空则调用对应服务，对应服务方法报错
            if (executeResultEntity.getBody().getSuccess() != null) {
                executeResult = executeResultEntity.getBody();
            } else {
                executeResult = new ExecuteResult(false, null, ERROR_VALIDATORGUARD);
            }
        } catch (Exception e) {
            LOGGER.error(EXCEPTION, e);
            executeResult = new ExecuteResult(false, null, ERROR_VALIDATORGUARD);
        }

        Map<Object, Object> variables = context.getExtendedState().getVariables();
        variables.put("executeResult", executeResult);
        return executeResult.getSuccess();
    }

    @Override
    public Boolean postpositionAction(Long organizationId, String serviceCode, Long transfId, Long instanceId, StateContext<String, String> context) {
        List<StateMachineConfigDTO> configs = postposition(organizationId, transfId);
        //节点转状态
        Long targetStateId = nodeMapper.getNodeById(Long.parseLong(context.getTarget().getId())).getStateId();
        ExecuteResult executeResult;
        //调用对应服务，执行动作，返回是否成功
        try {
            ResponseEntity<ExecuteResult> executeResultEntity = customFeignClientAdaptor.executeConfig(getURI(serviceCode, organizationId, METHOD_EXECUTE_CONFIG, instanceId, targetStateId, null, StateMachineConfigType.STATUS_POSTPOSITION.value()), configs);
            //返回为空则调用对应服务，对应服务方法报错
            if (executeResultEntity.getBody().getSuccess() != null) {
                executeResult = executeResultEntity.getBody();
            } else {
                executeResult = new ExecuteResult(false, null, "error.customFeignClientAdaptor.executeConfig.postpositionAction");
            }
        } catch (Exception e) {
            LOGGER.error(EXCEPTION, e);
            executeResult = new ExecuteResult(false, null, ERROR_VALIDATORGUARD);
        }
        Map<Object, Object> variables = context.getExtendedState().getVariables();
        variables.put("executeResult", executeResult);
        return executeResult.getSuccess();
    }

    @Override
    public List<StateMachineConfigDTO> condition(Long organizationId, Long transfId) {
        List<StateMachineConfigDTO> configs = configService.queryByTransfId(organizationId, transfId, StateMachineConfigType.STATUS_CONDITION.value());
        return configs == null ? Collections.emptyList() : configs;
    }

    @Override
    public List<StateMachineConfigDTO> validator(Long organizationId, Long transfId) {
        List<StateMachineConfigDTO> configs = configService.queryByTransfId(organizationId, transfId, StateMachineConfigType.STATUS_VALIDATOR.value());
        return configs == null ? Collections.emptyList() : configs;
    }

    @Override
    public List<StateMachineConfigDTO> trigger(Long organizationId, Long transfId) {
        List<StateMachineConfigDTO> configs = configService.queryByTransfId(organizationId, transfId, StateMachineConfigType.STATUS_TRIGGER.value());
        return configs == null ? Collections.emptyList() : configs;
    }

    @Override
    public List<StateMachineConfigDTO> postposition(Long organizationId, Long transfId) {
        List<StateMachineConfigDTO> configs = configService.queryByTransfId(organizationId, transfId, StateMachineConfigType.STATUS_POSTPOSITION.value());
        return configs == null ? Collections.emptyList() : configs;
    }

    /**
     * 获取feign的api url
     *
     * @param serviceCode       serviceCode
     * @param organizationId    organizationId
     * @param method            method
     * @param instanceId        instanceId
     * @param targetStateId     targetStateId
     * @param conditionStrategy conditionStrategy
     * @param type              type
     * @return URI
     */
    private URI getURI(String serviceCode, Long organizationId, String method, Long instanceId, Long targetStateId, String conditionStrategy, String type) {
        URI uri = null;
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("http://").append(serviceCode).append("/v1/organizations/").append(organizationId).append("/state_machine/").append(method).append("?1=1");
        if (instanceId != null) {
            stringBuilder.append("&instance_id=").append(instanceId);
        }
        if (targetStateId != null) {
            stringBuilder.append("&target_state_id=").append(targetStateId);
        }
        if (type != null) {
            stringBuilder.append("&type=").append(type);
        }
        if (conditionStrategy != null) {
            stringBuilder.append("&condition_strategy=").append(conditionStrategy);
        }
        LOGGER.info("uri:{}", Optional.of(stringBuilder).map(result -> stringBuilder.toString()));
        try {
            uri = new URI(stringBuilder.toString());
        } catch (URISyntaxException e) {
            LOGGER.error(EXCEPTION, e);
        }
        return uri;
    }
}
