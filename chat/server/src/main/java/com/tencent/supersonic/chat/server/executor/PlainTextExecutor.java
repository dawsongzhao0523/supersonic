package com.tencent.supersonic.chat.server.executor;

import com.tencent.supersonic.chat.server.agent.Agent;
import com.tencent.supersonic.chat.server.agent.MultiTurnConfig;
import com.tencent.supersonic.chat.server.parser.ParserConfig;
import com.tencent.supersonic.chat.server.persistence.repository.ChatQueryRepository;
import com.tencent.supersonic.chat.server.pojo.ExecuteContext;
import com.tencent.supersonic.chat.server.service.AgentService;
import com.tencent.supersonic.common.util.ContextUtils;
import com.tencent.supersonic.headless.api.pojo.response.ParseResp;
import com.tencent.supersonic.headless.api.pojo.response.QueryResult;
import com.tencent.supersonic.headless.api.pojo.response.QueryState;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.input.Prompt;
import dev.langchain4j.model.input.PromptTemplate;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.provider.ModelProvider;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static com.tencent.supersonic.chat.server.parser.ParserConfig.PARSER_MULTI_TURN_ENABLE;

public class PlainTextExecutor implements ChatQueryExecutor {

    private static final String INSTRUCTION = ""
            + "#Role: You are a nice person to talk to.\n"
            + "#Task: Respond quickly and nicely to the user."
            + "#Rules: 1.ALWAYS use the same language as the input.\n"
            + "#History Inputs: %s\n"
            + "#Current Input: %s\n"
            + "#Your response: ";

    @Override
    public QueryResult execute(ExecuteContext executeContext) {
        if (!"PLAIN_TEXT".equals(executeContext.getParseInfo().getQueryMode())) {
            return null;
        }

        String promptStr = String.format(INSTRUCTION, getHistoryInputs(executeContext),
                executeContext.getQueryText());
        Prompt prompt = PromptTemplate.from(promptStr).apply(Collections.EMPTY_MAP);

        AgentService agentService = ContextUtils.getBean(AgentService.class);
        Agent chatAgent = agentService.getAgent(executeContext.getAgent().getId());

        ChatLanguageModel chatLanguageModel = ModelProvider.getChatModel(chatAgent.getModelConfig());
        Response<AiMessage> response = chatLanguageModel.generate(prompt.toUserMessage());

        QueryResult result = new QueryResult();
        result.setQueryState(QueryState.SUCCESS);
        result.setQueryMode(executeContext.getParseInfo().getQueryMode());
        result.setTextResult(response.content().text());

        return result;
    }

    private String getHistoryInputs(ExecuteContext executeContext) {
        StringBuilder historyInput = new StringBuilder();

        AgentService agentService = ContextUtils.getBean(AgentService.class);
        Agent chatAgent = agentService.getAgent(executeContext.getAgent().getId());

        ParserConfig parserConfig = ContextUtils.getBean(ParserConfig.class);
        MultiTurnConfig agentMultiTurnConfig = chatAgent.getMultiTurnConfig();
        Boolean globalMultiTurnConfig = Boolean.valueOf(parserConfig.getParameterValue(PARSER_MULTI_TURN_ENABLE));
        Boolean multiTurnConfig = agentMultiTurnConfig != null
                ? agentMultiTurnConfig.isEnableMultiTurn() : globalMultiTurnConfig;

        if (Boolean.TRUE.equals(multiTurnConfig)) {
            List<ParseResp> parseResps = getHistoryParseResult(executeContext.getChatId(), 5);
            parseResps.stream().forEach(p -> {
                historyInput.append(p.getQueryText());
                historyInput.append(";");
            });
        }

        return historyInput.toString();
    }

    private List<ParseResp> getHistoryParseResult(int chatId, int multiNum) {
        ChatQueryRepository chatQueryRepository = ContextUtils.getBean(ChatQueryRepository.class);
        List<ParseResp> contextualParseInfoList = chatQueryRepository.getContextualParseInfo(chatId)
                .stream().filter(p -> p.getState() == ParseResp.ParseState.COMPLETED).collect(Collectors.toList());

        List<ParseResp> contextualList = contextualParseInfoList.subList(0,
                Math.min(multiNum, contextualParseInfoList.size()));
        Collections.reverse(contextualList);

        return contextualList;
    }

}
