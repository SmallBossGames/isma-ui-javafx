module isma.ui.external.services {
    requires kotlin.stdlib;
    requires kotlinx.coroutines.core;
    requires kotlinx.serialization.json;
    requires isma.ui.domain;
    requires isma.jvm.lib.exchange.format;
    requires org.slf4j;

    exports ru.isma.next.external;
    exports ru.isma.next.external.cli;
    exports ru.isma.next.external.dtos;
    exports ru.isma.next.external.lsp;
}
