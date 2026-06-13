package com.tcm.dispenser.view;

import com.tcm.dispenser.model.*;
import com.tcm.dispenser.service.DispenserService;
import com.tcm.dispenser.service.PrescriptionLoader;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.*;

public class MainPanel extends BorderPane {

    private final DispenserService dispenserService;

    private Label statusLabel;
    private Label statusValueLabel;
    private Label prescriptionInfoLabel;
    private Button btnStart;
    private Button btnEmergencyStop;
    private Button btnLoadPrescription;
    private TableView<MedicineBin> binTable;
    private TableView<DispenseTask> taskTable;
    private ProgressBar overallProgress;
    private Label progressLabel;
    private Label currentWeightLabel;
    private Canvas weightChartCanvas;
    private GraphicsContext weightChartGC;
    private ListView<String> blockageEventList;
    private VBox chartPanel;

    private List<WeightSample> weightSamples = new ArrayList<>();
    private long chartStartTime = 0;
    private double maxChartWeight = 0.0;
    private final int maxChartPoints = 300;

    private final Map<Integer, String> binNameCache = new HashMap<>();
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss");

    private Prescription currentPrescription;

    public MainPanel(Stage primaryStage) {
        this.dispenserService = new DispenserService();
        initializeUI(primaryStage);
        setupCallbacks();
        dispenserService.initialize();
    }

    private void initializeUI(Stage primaryStage) {
        setTop(createTopBar());
        setCenter(createCenterPane());
        setBottom(createBottomBar());
        setRight(createRightPanel());
    }

    private HBox createTopBar() {
        HBox topBar = new HBox(20);
        topBar.setPadding(new Insets(15, 20, 15, 20));
        topBar.setStyle("-fx-background-color: linear-gradient(to right, #1a237e, #283593);");
        topBar.setAlignment(Pos.CENTER_LEFT);

        Label titleLabel = new Label("智能中药配方颗粒调剂机");
        titleLabel.setFont(Font.font("Microsoft YaHei", FontWeight.BOLD, 24));
        titleLabel.setTextFill(Color.WHITE);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Label hospitalLabel = new Label("24h社区医院");
        hospitalLabel.setFont(Font.font("Microsoft YaHei", 14));
        hospitalLabel.setTextFill(Color.web("#90CAF9"));

        statusLabel = new Label("系统状态:");
        statusLabel.setFont(Font.font("Microsoft YaHei", 13));
        statusLabel.setTextFill(Color.web("#B0BEC5"));

        statusValueLabel = new Label("空闲");
        statusValueLabel.setFont(Font.font("Microsoft YaHei", FontWeight.BOLD, 14));
        statusValueLabel.setTextFill(Color.web("#4CAF50"));

        topBar.getChildren().addAll(titleLabel, spacer, hospitalLabel, statusLabel, statusValueLabel);
        return topBar;
    }

    private VBox createCenterPane() {
        VBox center = new VBox(8);
        center.setPadding(new Insets(8));
        center.setStyle("-fx-background-color: #ECEFF1;");

        HBox topTables = new HBox(10);
        topTables.setPrefHeight(300);

        VBox binBox = new VBox(5);
        VBox.setVgrow(binBox, Priority.ALWAYS);
        Label binSectionTitle = new Label("药仓状态监控");
        binSectionTitle.setFont(Font.font("Microsoft YaHei", FontWeight.BOLD, 15));
        binSectionTitle.setStyle("-fx-text-fill: #1a237e;");
        binTable = createBinTable();
        VBox.setVgrow(binTable, Priority.ALWAYS);
        binBox.getChildren().addAll(binSectionTitle, binTable);

        VBox taskBox = new VBox(5);
        VBox.setVgrow(taskBox, Priority.ALWAYS);
        Label taskSectionTitle = new Label("调剂任务列表");
        taskSectionTitle.setFont(Font.font("Microsoft YaHei", FontWeight.BOLD, 15));
        taskSectionTitle.setStyle("-fx-text-fill: #1a237e;");
        taskTable = createTaskTable();
        VBox.setVgrow(taskTable, Priority.ALWAYS);
        taskBox.getChildren().addAll(taskSectionTitle, taskTable);

        topTables.getChildren().addAll(binBox, taskBox);
        HBox.setHgrow(binBox, Priority.ALWAYS);
        HBox.setHgrow(taskBox, Priority.ALWAYS);

        VBox chartBox = new VBox(5);
        VBox.setVgrow(chartBox, Priority.ALWAYS);
        HBox chartHeader = new HBox(15);
        chartHeader.setAlignment(Pos.CENTER_LEFT);
        Label chartTitle = new Label("实时重量增量曲线");
        chartTitle.setFont(Font.font("Microsoft YaHei", FontWeight.BOLD, 15));
        chartTitle.setStyle("-fx-text-fill: #1a237e;");

        currentWeightLabel = new Label("当前重量: -- g");
        currentWeightLabel.setFont(Font.font("Microsoft YaHei", FontWeight.BOLD, 14));
        currentWeightLabel.setStyle("-fx-text-fill: #E65100;");

        Button btnClearChart = new Button("清空曲线");
        btnClearChart.setFont(Font.font("Microsoft YaHei", 11));
        btnClearChart.setOnAction(e -> clearWeightChart());

        chartHeader.getChildren().addAll(chartTitle, currentWeightLabel, btnClearChart);

        chartPanel = new VBox();
        chartPanel.setStyle("-fx-background-color: white; -fx-border-color: #BDBDBD; -fx-border-radius: 4;");
        chartPanel.setPadding(new Insets(5));
        VBox.setVgrow(chartPanel, Priority.ALWAYS);

        weightChartCanvas = new Canvas(800, 220);
        weightChartGC = weightChartCanvas.getGraphicsContext2D();
        chartPanel.getChildren().add(weightChartCanvas);

        weightChartCanvas.widthProperty().bind(chartPanel.widthProperty().subtract(12));
        weightChartCanvas.heightProperty().bind(chartPanel.heightProperty().subtract(12));
        weightChartCanvas.widthProperty().addListener(obs -> redrawWeightChart());
        weightChartCanvas.heightProperty().addListener(obs -> redrawWeightChart());

        chartBox.getChildren().addAll(chartHeader, chartPanel);

        Label blockageTitle = new Label("堵料异常记录");
        blockageTitle.setFont(Font.font("Microsoft YaHei", FontWeight.BOLD, 15));
        blockageTitle.setStyle("-fx-text-fill: #B71C1C;");

        blockageEventList = new ListView<>();
        blockageEventList.setPrefHeight(100);
        blockageEventList.setStyle("-fx-control-inner-background: #FFF3E0;");

        center.getChildren().addAll(topTables, chartBox, blockageTitle, blockageEventList);
        return center;
    }

    @SuppressWarnings("unchecked")
    private TableView<MedicineBin> createBinTable() {
        TableView<MedicineBin> table = new TableView<>();
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.setRowFactory(tv -> new TableRow<MedicineBin>() {
            @Override
            protected void updateItem(MedicineBin item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setStyle("");
                } else {
                    binNameCache.put(item.getId(), item.getName());
                    double pct = item.getRemainingPercentage();
                    if (pct < 10) {
                        setStyle("-fx-background-color: #FFCDD2;");
                    } else if (pct < 25) {
                        setStyle("-fx-background-color: #FFE0B2;");
                    } else {
                        setStyle("-fx-background-color: white;");
                    }
                }
            }
        });

        TableColumn<MedicineBin, Integer> colId = new TableColumn<>("仓号");
        colId.setCellValueFactory(new PropertyValueFactory<>("id"));
        colId.setPrefWidth(45);

        TableColumn<MedicineBin, String> colName = new TableColumn<>("药味");
        colName.setCellValueFactory(new PropertyValueFactory<>("name"));
        colName.setPrefWidth(80);

        TableColumn<MedicineBin, Double> colRemaining = new TableColumn<>("剩余(g)");
        colRemaining.setCellValueFactory(new PropertyValueFactory<>("remainingGrams"));
        colRemaining.setPrefWidth(75);
        colRemaining.setCellFactory(col -> new TableCell<MedicineBin, Double>() {
            @Override
            protected void updateItem(Double val, boolean empty) {
                super.updateItem(val, empty);
                if (empty || val == null) {
                    setText(null);
                    setStyle("");
                } else {
                    setText(String.format("%.1f", val));
                    setAlignment(Pos.CENTER_RIGHT);
                }
            }
        });

        TableColumn<MedicineBin, Double> colCapacity = new TableColumn<>("容量(g)");
        colCapacity.setCellValueFactory(new PropertyValueFactory<>("capacityGrams"));
        colCapacity.setPrefWidth(65);

        TableColumn<MedicineBin, Void> colLevel = new TableColumn<>("余量");
        colLevel.setPrefWidth(120);
        colLevel.setCellFactory(col -> new TableCell<MedicineBin, Void>() {
            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) {
                    setGraphic(null);
                    setText(null);
                } else {
                    MedicineBin bin = getTableView().getItems().get(getIndex());
                    HBox box = new HBox(5);
                    box.setAlignment(Pos.CENTER_LEFT);
                    ProgressBar pb = new ProgressBar(bin.getRemainingPercentage() / 100.0);
                    pb.setPrefWidth(60);
                    if (bin.getRemainingPercentage() < 10) {
                        pb.setStyle("-fx-accent: #F44336;");
                    } else if (bin.getRemainingPercentage() < 25) {
                        pb.setStyle("-fx-accent: #FF9800;");
                    } else {
                        pb.setStyle("-fx-accent: #4CAF50;");
                    }
                    Label lbl = new Label(bin.getLevelLabel());
                    lbl.setFont(Font.font(9));
                    box.getChildren().addAll(pb, lbl);
                    setGraphic(box);
                }
            }
        });

        TableColumn<MedicineBin, Boolean> colOnline = new TableColumn<>("状态");
        colOnline.setCellValueFactory(new PropertyValueFactory<>("online"));
        colOnline.setPrefWidth(50);
        colOnline.setCellFactory(col -> new TableCell<MedicineBin, Boolean>() {
            @Override
            protected void updateItem(Boolean val, boolean empty) {
                super.updateItem(val, empty);
                if (empty || val == null) {
                    setText(null);
                } else {
                    setText(val ? "在线" : "离线");
                    setTextFill(val ? Color.web("#4CAF50") : Color.web("#F44336"));
                    setFont(Font.font("Microsoft YaHei", FontWeight.BOLD, 10));
                }
            }
        });

        table.getColumns().addAll(colId, colName, colRemaining, colCapacity, colLevel, colOnline);
        return table;
    }

    @SuppressWarnings("unchecked")
    private TableView<DispenseTask> createTaskTable() {
        TableView<DispenseTask> table = new TableView<>();
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        TableColumn<DispenseTask, Integer> colBinId = new TableColumn<>("仓号");
        colBinId.setCellValueFactory(new PropertyValueFactory<>("binId"));
        colBinId.setPrefWidth(45);

        TableColumn<DispenseTask, String> colMedicine = new TableColumn<>("药味");
        colMedicine.setCellValueFactory(new PropertyValueFactory<>("medicineName"));
        colMedicine.setPrefWidth(70);

        TableColumn<DispenseTask, Double> colTarget = new TableColumn<>("目标(g)");
        colTarget.setCellValueFactory(new PropertyValueFactory<>("targetGrams"));
        colTarget.setPrefWidth(60);
        colTarget.setCellFactory(col -> new TableCell<DispenseTask, Double>() {
            @Override
            protected void updateItem(Double val, boolean empty) {
                super.updateItem(val, empty);
                setText(empty || val == null ? null : String.format("%.2f", val));
                setAlignment(Pos.CENTER_RIGHT);
            }
        });

        TableColumn<DispenseTask, Double> colWeighed = new TableColumn<>("实测(g)");
        colWeighed.setCellValueFactory(new PropertyValueFactory<>("actualWeighedGrams"));
        colWeighed.setPrefWidth(60);
        colWeighed.setCellFactory(col -> new TableCell<DispenseTask, Double>() {
            @Override
            protected void updateItem(Double val, boolean empty) {
                super.updateItem(val, empty);
                if (empty || val == null || val <= 0) {
                    setText("-");
                    setAlignment(Pos.CENTER_RIGHT);
                } else {
                    setText(String.format("%.3f", val));
                    setAlignment(Pos.CENTER_RIGHT);
                }
            }
        });

        TableColumn<DispenseTask, Double> colDeficit = new TableColumn<>("欠量(g)");
        colDeficit.setCellValueFactory(new PropertyValueFactory<>("deficitGrams"));
        colDeficit.setPrefWidth(60);
        colDeficit.setCellFactory(col -> new TableCell<DispenseTask, Double>() {
            @Override
            protected void updateItem(Double val, boolean empty) {
                super.updateItem(val, empty);
                if (empty || val == null || val <= 0) {
                    setText("-");
                    setStyle("");
                } else if (val >= 0.2) {
                    setText(String.format("%.3f", val));
                    setTextFill(Color.RED);
                    setFont(Font.font("Microsoft YaHei", FontWeight.BOLD, 11));
                    setAlignment(Pos.CENTER_RIGHT);
                } else {
                    setText(String.format("%.3f", val));
                    setAlignment(Pos.CENTER_RIGHT);
                }
            }
        });

        TableColumn<DispenseTask, String> colState = new TableColumn<>("阶段");
        colState.setCellValueFactory(new PropertyValueFactory<>("statusText"));
        colState.setPrefWidth(100);
        colState.setCellFactory(col -> new TableCell<DispenseTask, String>() {
            @Override
            protected void updateItem(String val, boolean empty) {
                super.updateItem(val, empty);
                if (empty || val == null) {
                    setText(null);
                    setStyle("");
                } else {
                    setText(val);
                    setFont(Font.font("Microsoft YaHei", 10));
                    if (val.contains("堵料")) {
                        setTextFill(Color.RED);
                    } else if (val.contains("补差")) {
                        setTextFill(Color.ORANGE);
                    } else if (val.contains("完成")) {
                        setTextFill(Color.GREEN);
                    } else if (val.contains("称重")) {
                        setTextFill(Color.BLUE);
                    }
                }
            }
        });

        TableColumn<DispenseTask, Void> colProgress = new TableColumn<>("进度");
        colProgress.setPrefWidth(110);
        colProgress.setCellFactory(col -> new TableCell<DispenseTask, Void>() {
            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) {
                    setGraphic(null);
                } else {
                    DispenseTask task = getTableView().getItems().get(getIndex());
                    HBox box = new HBox(5);
                    box.setAlignment(Pos.CENTER_LEFT);
                    ProgressBar pb = new ProgressBar(task.getProgress() / 100.0);
                    pb.setPrefWidth(60);
                    if (task.isBlockageOccurred()) {
                        pb.setStyle("-fx-accent: #F44336;");
                    } else if (task.getCompensationAttempts() > 0) {
                        pb.setStyle("-fx-accent: #FF9800;");
                    } else {
                        pb.setStyle("-fx-accent: #1E88E5;");
                    }
                    Label lbl = new Label(String.format("%.1f%%", task.getProgress()));
                    lbl.setFont(Font.font(9));
                    box.getChildren().addAll(pb, lbl);
                    setGraphic(box);
                }
            }
        });

        table.getColumns().addAll(colBinId, colMedicine, colTarget, colWeighed, colDeficit, colState, colProgress);
        return table;
    }

    private VBox createRightPanel() {
        VBox right = new VBox(15);
        right.setPadding(new Insets(15));
        right.setPrefWidth(210);
        right.setStyle("-fx-background-color: #FAFAFA; -fx-border-color: #E0E0E0; -fx-border-width: 0 0 0 1;");

        Label ctrlTitle = new Label("控制面板");
        ctrlTitle.setFont(Font.font("Microsoft YaHei", FontWeight.BOLD, 16));
        ctrlTitle.setStyle("-fx-text-fill: #1a237e;");

        Separator sep1 = new Separator();

        btnLoadPrescription = new Button("加载处方文件");
        btnLoadPrescription.setPrefWidth(180);
        btnLoadPrescription.setStyle("-fx-background-color: #1565C0; -fx-text-fill: white; " +
                "-fx-font-size: 13px; -fx-font-family: 'Microsoft YaHei'; -fx-padding: 9 0;");
        btnLoadPrescription.setOnAction(e -> loadPrescriptionFile());

        prescriptionInfoLabel = new Label("未加载处方");
        prescriptionInfoLabel.setWrapText(true);
        prescriptionInfoLabel.setStyle("-fx-text-fill: #757575; -fx-font-size: 11px;");
        prescriptionInfoLabel.setPrefWidth(180);

        Separator sep2 = new Separator();

        btnStart = new Button("开始批量抓药");
        btnStart.setPrefWidth(180);
        btnStart.setStyle("-fx-background-color: #2E7D32; -fx-text-fill: white; " +
                "-fx-font-size: 15px; -fx-font-weight: bold; -fx-font-family: 'Microsoft YaHei'; -fx-padding: 12 0;");
        btnStart.setDisable(true);
        btnStart.setOnAction(e -> startDispense());

        btnEmergencyStop = new Button("紧急中止");
        btnEmergencyStop.setPrefWidth(180);
        btnEmergencyStop.setStyle("-fx-background-color: #C62828; -fx-text-fill: white; " +
                "-fx-font-size: 15px; -fx-font-weight: bold; -fx-font-family: 'Microsoft YaHei'; -fx-padding: 12 0;");
        btnEmergencyStop.setDisable(true);
        btnEmergencyStop.setOnAction(e -> doEmergencyStop());

        Separator sep3 = new Separator();

        Label progressTitle = new Label("调剂进度");
        progressTitle.setFont(Font.font("Microsoft YaHei", FontWeight.BOLD, 13));
        progressTitle.setStyle("-fx-text-fill: #1a237e;");

        overallProgress = new ProgressBar(0);
        overallProgress.setPrefWidth(180);
        overallProgress.setStyle("-fx-accent: #1E88E5;");

        progressLabel = new Label("0.0%");
        progressLabel.setFont(Font.font("Microsoft YaHei", FontWeight.BOLD, 18));
        progressLabel.setStyle("-fx-text-fill: #1565C0;");

        right.getChildren().addAll(ctrlTitle, sep1, btnLoadPrescription, prescriptionInfoLabel,
                sep2, btnStart, btnEmergencyStop, sep3, progressTitle, overallProgress, progressLabel);
        return right;
    }

    private HBox createBottomBar() {
        HBox bottom = new HBox(20);
        bottom.setPadding(new Insets(8, 20, 8, 20));
        bottom.setStyle("-fx-background-color: #37474F;");
        bottom.setAlignment(Pos.CENTER_LEFT);

        Label versionLabel = new Label("TCM-Dispenser v2.0.0  |  重量闭环+堵料防卡  |  JNI+C++ 跨语言架构");
        versionLabel.setTextFill(Color.web("#90A4AE"));
        versionLabel.setFont(Font.font(11));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Label timeLabel = new Label();
        timeLabel.setTextFill(Color.web("#B0BEC5"));
        timeLabel.setFont(Font.font(11));
        timeLabel.setText(java.time.LocalDateTime.now().format(
                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));

        bottom.getChildren().addAll(versionLabel, spacer, timeLabel);
        return bottom;
    }

    private void setupCallbacks() {
        dispenserService.setStatusCallback(status -> Platform.runLater(() -> {
            statusValueLabel.setText(status.getLabel());
            switch (status) {
                case IDLE:
                    statusValueLabel.setTextFill(Color.web("#4CAF50"));
                    btnStart.setDisable(currentPrescription == null);
                    btnEmergencyStop.setDisable(true);
                    break;
                case DISPENSING:
                    statusValueLabel.setTextFill(Color.web("#1E88E5"));
                    btnStart.setDisable(true);
                    btnEmergencyStop.setDisable(false);
                    break;
                case PAUSED:
                    statusValueLabel.setTextFill(Color.web("#FF9800"));
                    break;
                case ERROR:
                    statusValueLabel.setTextFill(Color.web("#F44336"));
                    btnStart.setDisable(true);
                    btnEmergencyStop.setDisable(false);
                    break;
                case EMERGENCY_STOP:
                    statusValueLabel.setTextFill(Color.web("#F44336"));
                    btnStart.setDisable(true);
                    btnEmergencyStop.setDisable(true);
                    break;
            }
        }));

        dispenserService.setBinStatusCallback(bins -> Platform.runLater(() -> {
            binTable.getItems().setAll(bins);
        }));

        dispenserService.setWeightSampleCallback(samples -> Platform.runLater(() -> {
            for (WeightSample sample : samples) {
                addWeightSample(sample);
            }
        }));

        dispenserService.setBlockageEventCallback(events -> Platform.runLater(() -> {
            for (BlockageEvent event : events) {
                addBlockageEvent(event);
            }
        }));

        dispenserService.setTaskStatusCallback(tasks -> Platform.runLater(() -> {
            for (int i = 0; i < tasks.size() && i < taskTable.getItems().size(); i++) {
                DispenseTask updated = tasks.get(i);
                DispenseTask existing = taskTable.getItems().get(i);
                existing.setDispensedGrams(updated.getDispensedGrams());
                existing.setActualWeighedGrams(updated.getActualWeighedGrams());
                existing.setDeficitGrams(updated.getDeficitGrams());
                existing.setCompensationAttempts(updated.getCompensationAttempts());
                existing.setCompensationStateCode(updated.getCompensationStateCode());
                existing.setBlockageOccurred(updated.isBlockageOccurred());
                existing.setBlockageCount(updated.getBlockageCount());
                existing.setCompleted(updated.isCompleted());
                existing.setHasError(updated.isHasError());
                existing.setErrorMessage(updated.getErrorMessage());
            }
            taskTable.refresh();
            updateOverallProgress();
        }));
    }

    private void addWeightSample(WeightSample sample) {
        if (weightSamples.isEmpty()) {
            chartStartTime = sample.getTimestampMs();
        }
        weightSamples.add(sample);
        while (weightSamples.size() > maxChartPoints) {
            weightSamples.remove(0);
        }

        double currentW = sample.getWeightGrams();
        currentWeightLabel.setText(String.format("当前重量: %.3f g", currentW));
        if (currentW > maxChartWeight) {
            maxChartWeight = currentW;
        }

        redrawWeightChart();
    }

    private void clearWeightChart() {
        weightSamples.clear();
        maxChartWeight = 0;
        chartStartTime = 0;
        currentWeightLabel.setText("当前重量: -- g");
        redrawWeightChart();
    }

    private void redrawWeightChart() {
        double w = weightChartCanvas.getWidth();
        double h = weightChartCanvas.getHeight();
        if (w <= 0 || h <= 0) return;

        weightChartGC.setFill(Color.WHITE);
        weightChartGC.fillRect(0, 0, w, h);

        double paddingLeft = 50;
        double paddingRight = 20;
        double paddingTop = 20;
        double paddingBottom = 30;
        double chartW = w - paddingLeft - paddingRight;
        double chartH = h - paddingTop - paddingBottom;

        weightChartGC.setStroke(Color.LIGHTGRAY);
        weightChartGC.setLineWidth(0.5);
        for (int i = 0; i <= 5; i++) {
            double y = paddingTop + (chartH / 5) * i;
            weightChartGC.strokeLine(paddingLeft, y, paddingLeft + chartW, y);
        }

        weightChartGC.setStroke(Color.BLACK);
        weightChartGC.setLineWidth(1);
        weightChartGC.strokeRect(paddingLeft, paddingTop, chartW, chartH);

        if (weightSamples.size() < 2) {
            weightChartGC.setFill(Color.GRAY);
            weightChartGC.setFont(Font.font(12));
            weightChartGC.fillText("等待重量数据...", paddingLeft + 10, paddingTop + 20);
            return;
        }

        double timeRange = weightSamples.get(weightSamples.size() - 1).getTimestampMs() - weightSamples.get(0).getTimestampMs();
        if (timeRange < 5000) timeRange = 5000;

        double weightRange = maxChartWeight > 0.1 ? maxChartWeight * 1.15 : 1.0;

        weightChartGC.setFill(Color.BLACK);
        weightChartGC.setFont(Font.font(10));
        for (int i = 0; i <= 5; i++) {
            double weightVal = (weightRange / 5) * (5 - i);
            String label = String.format("%.2f", weightVal);
            weightChartGC.fillText(label, 5, paddingTop + (chartH / 5) * i + 4);
        }

        int totalPoints = weightSamples.size();
        if (totalPoints > 1) {
            long startTs = weightSamples.get(0).getTimestampMs();

            weightChartGC.setStroke(Color.RED);
            weightChartGC.setLineWidth(1.5);
            weightChartGC.beginPath();
            for (int i = 0; i < totalPoints; i++) {
                WeightSample s = weightSamples.get(i);
                double x = paddingLeft + ((s.getTimestampMs() - startTs) / timeRange) * chartW;
                double y = paddingTop + chartH - (s.getWeightGrams() / weightRange) * chartH;
                if (i == 0) {
                    weightChartGC.moveTo(x, y);
                } else {
                    weightChartGC.lineTo(x, y);
                }
            }
            weightChartGC.stroke();

            weightChartGC.setFill(Color.RED);
            for (int i = 0; i < totalPoints; i += 5) {
                WeightSample s = weightSamples.get(i);
                double x = paddingLeft + ((s.getTimestampMs() - startTs) / timeRange) * chartW;
                double y = paddingTop + chartH - (s.getWeightGrams() / weightRange) * chartH;
                weightChartGC.fillOval(x - 1.5, y - 1.5, 3, 3);
            }

            if (totalPoints >= 2) {
                WeightSample first = weightSamples.get(0);
                WeightSample last = weightSamples.get(weightSamples.size() - 1);
                double increment = last.getWeightGrams() - first.getWeightGrams();
                weightChartGC.setFill(Color.DARKBLUE);
                weightChartGC.setFont(Font.font("Microsoft YaHei", FontWeight.BOLD, 12));
                String incrText = String.format("增量: %.3f g", increment);
                weightChartGC.fillText(incrText, paddingLeft + chartW - 120, paddingTop - 5);
            }

            int labelCount = 5;
            for (int i = 0; i <= labelCount; i++) {
                long ts = startTs + (timeRange / labelCount) * i;
                String timeStr = timeFormat.format(new Date(ts));
                double x = paddingLeft + (chartW / labelCount) * i;
                weightChartGC.setFill(Color.BLACK);
                weightChartGC.setFont(Font.font(9));
                weightChartGC.fillText(timeStr, x - 25, h - 8);
            }
        }
    }

    private void addBlockageEvent(BlockageEvent event) {
        String binName = binNameCache.getOrDefault(event.getBinId(), "药仓" + event.getBinId());
        String timeStr = timeFormat.format(new Date(event.getTimestampMs()));
        String status = event.isResolved() ? "✓已解除" : "⚠处理中";
        String msg = String.format("[%s] %s (仓%d) 第%d次: %s %s",
                timeStr, binName, event.getBinId(),
                event.getAttempt(), event.getMessage(), status);

        blockageEventList.getItems().add(0, msg);
        if (blockageEventList.getItems().size() > 50) {
            blockageEventList.getItems().remove(blockageEventList.getItems().size() - 1);
        }

        if (!event.isResolved()) {
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle("堵料异常警告");
            alert.setHeaderText(binName + " 检测到堵料");
            alert.setContentText(String.format("时间: %s\n药仓: %s (#%d)\n事件: %s\n\n系统正在自动执行反转防卡处理...",
                    timeStr, binName, event.getBinId(), event.getMessage()));
            alert.show();
        }
    }

    private void updateOverallProgress() {
        if (taskTable.getItems().isEmpty()) {
            overallProgress.setProgress(0);
            progressLabel.setText("0.0%");
            return;
        }
        double total = 0;
        for (DispenseTask task : taskTable.getItems()) {
            total += task.getProgress();
        }
        double avg = total / taskTable.getItems().size();
        overallProgress.setProgress(avg / 100.0);
        progressLabel.setText(String.format("%.1f%%", avg));
    }

    private void loadPrescriptionFile() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("选择处方文件");
        fileChooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("JSON 处方文件", "*.json"),
                new FileChooser.ExtensionFilter("所有文件", "*.*")
        );
        File file = fileChooser.showOpenDialog(getScene().getWindow());
        if (file == null) return;

        try {
            PrescriptionLoader loader = new PrescriptionLoader();
            currentPrescription = loader.loadFromFile(file.getAbsolutePath());

            prescriptionInfoLabel.setText(String.format("处方号: %s\n患者: %s\n医生: %s\n剂数: %d  药味数: %d",
                    currentPrescription.getPrescriptionId(),
                    currentPrescription.getPatientName(),
                    currentPrescription.getDoctorName(),
                    currentPrescription.getDosageCount(),
                    currentPrescription.getItems().size()));
            prescriptionInfoLabel.setStyle("-fx-text-fill: #212121; -fx-font-size: 11px;");

            List<DispenseTask> tasks = currentPrescription.toDispenseTasks();
            for (DispenseTask task : tasks) {
                String name = binNameCache.get(task.getBinId());
                if (name != null) {
                    task.setMedicineName(name);
                }
            }
            taskTable.getItems().setAll(tasks);
            btnStart.setDisable(false);
            clearWeightChart();
            blockageEventList.getItems().clear();

        } catch (Exception e) {
            showErrorAlert("处方加载失败", "无法解析处方文件:\n" + e.getMessage());
        }
    }

    private void startDispense() {
        if (currentPrescription == null) {
            showErrorAlert("操作提示", "请先加载处方文件");
            return;
        }

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("确认调剂");
        confirm.setHeaderText("即将开始批量抓药");
        confirm.setContentText(String.format("处方号: %s\n患者: %s\n剂数: %d\n药味数: %d\n\n确认开始？\n\n系统将自动执行重量闭环补差和堵料防卡。",
                currentPrescription.getPrescriptionId(),
                currentPrescription.getPatientName(),
                currentPrescription.getDosageCount(),
                currentPrescription.getItems().size()));

        Optional<ButtonType> result = confirm.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            clearWeightChart();
            blockageEventList.getItems().clear();
            int taskId = dispenserService.startDispense(currentPrescription);
            if (taskId < 0) {
                showErrorAlert("调剂失败", "无法启动调剂任务，请检查设备状态");
            }
        }
    }

    private void doEmergencyStop() {
        Alert confirm = new Alert(Alert.AlertType.WARNING);
        confirm.setTitle("紧急中止确认");
        confirm.setHeaderText("确认紧急中止？");
        confirm.setContentText("紧急中止将立即停止所有出药操作，已出的药品不可恢复。");

        Optional<ButtonType> result = confirm.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            dispenserService.emergencyStop();
        }
    }

    private void showErrorAlert(String title, String content) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(content);
        alert.showAndWait();
    }

    public void shutdown() {
        dispenserService.shutdown();
    }
}
