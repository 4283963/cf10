package com.tcm.dispenser.view;

import com.tcm.dispenser.model.DispenserStatus;
import com.tcm.dispenser.model.MedicineBin;
import com.tcm.dispenser.model.Prescription;
import com.tcm.dispenser.model.DispenseTask;
import com.tcm.dispenser.service.DispenserService;
import com.tcm.dispenser.service.PrescriptionLoader;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.util.List;
import java.util.Optional;

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
        VBox center = new VBox(10);
        center.setPadding(new Insets(10));
        center.setStyle("-fx-background-color: #ECEFF1;");

        Label binSectionTitle = new Label("药仓状态监控");
        binSectionTitle.setFont(Font.font("Microsoft YaHei", FontWeight.BOLD, 16));
        binSectionTitle.setStyle("-fx-text-fill: #1a237e;");

        binTable = createBinTable();

        Label taskSectionTitle = new Label("调剂任务列表");
        taskSectionTitle.setFont(Font.font("Microsoft YaHei", FontWeight.BOLD, 16));
        taskSectionTitle.setStyle("-fx-text-fill: #1a237e;");

        taskTable = createTaskTable();

        center.getChildren().addAll(binSectionTitle, binTable, taskSectionTitle, taskTable);
        VBox.setVgrow(binTable, Priority.ALWAYS);
        VBox.setVgrow(taskTable, Priority.ALWAYS);
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

        TableColumn<MedicineBin, Integer> colId = new TableColumn<>("药仓号");
        colId.setCellValueFactory(new PropertyValueFactory<>("id"));
        colId.setPrefWidth(60);

        TableColumn<MedicineBin, String> colName = new TableColumn<>("药味名称");
        colName.setCellValueFactory(new PropertyValueFactory<>("name"));
        colName.setPrefWidth(100);

        TableColumn<MedicineBin, String> colCode = new TableColumn<>("编码");
        colCode.setCellValueFactory(new PropertyValueFactory<>("code"));
        colCode.setPrefWidth(70);

        TableColumn<MedicineBin, Double> colRemaining = new TableColumn<>("剩余(g)");
        colRemaining.setCellValueFactory(new PropertyValueFactory<>("remainingGrams"));
        colRemaining.setPrefWidth(90);
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
        colCapacity.setPrefWidth(80);

        TableColumn<MedicineBin, Void> colLevel = new TableColumn<>("余量");
        colLevel.setPrefWidth(150);
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
                    pb.setPrefWidth(80);
                    if (bin.getRemainingPercentage() < 10) {
                        pb.setStyle("-fx-accent: #F44336;");
                    } else if (bin.getRemainingPercentage() < 25) {
                        pb.setStyle("-fx-accent: #FF9800;");
                    } else {
                        pb.setStyle("-fx-accent: #4CAF50;");
                    }
                    Label lbl = new Label(bin.getLevelLabel());
                    lbl.setFont(Font.font(10));
                    box.getChildren().addAll(pb, lbl);
                    setGraphic(box);
                }
            }
        });

        TableColumn<MedicineBin, Boolean> colOnline = new TableColumn<>("状态");
        colOnline.setCellValueFactory(new PropertyValueFactory<>("online"));
        colOnline.setPrefWidth(60);
        colOnline.setCellFactory(col -> new TableCell<MedicineBin, Boolean>() {
            @Override
            protected void updateItem(Boolean val, boolean empty) {
                super.updateItem(val, empty);
                if (empty || val == null) {
                    setText(null);
                } else {
                    setText(val ? "在线" : "离线");
                    setTextFill(val ? Color.web("#4CAF50") : Color.web("#F44336"));
                    setFont(Font.font("Microsoft YaHei", FontWeight.BOLD, 11));
                }
            }
        });

        table.getColumns().addAll(colId, colName, colCode, colRemaining, colCapacity, colLevel, colOnline);
        return table;
    }

    @SuppressWarnings("unchecked")
    private TableView<DispenseTask> createTaskTable() {
        TableView<DispenseTask> table = new TableView<>();
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        TableColumn<DispenseTask, Integer> colBinId = new TableColumn<>("药仓号");
        colBinId.setCellValueFactory(new PropertyValueFactory<>("binId"));
        colBinId.setPrefWidth(60);

        TableColumn<DispenseTask, String> colMedicine = new TableColumn<>("药味");
        colMedicine.setCellValueFactory(new PropertyValueFactory<>("medicineName"));
        colMedicine.setPrefWidth(100);

        TableColumn<DispenseTask, Double> colTarget = new TableColumn<>("目标(g)");
        colTarget.setCellValueFactory(new PropertyValueFactory<>("targetGrams"));
        colTarget.setPrefWidth(80);
        colTarget.setCellFactory(col -> new TableCell<DispenseTask, Double>() {
            @Override
            protected void updateItem(Double val, boolean empty) {
                super.updateItem(val, empty);
                setText(empty || val == null ? null : String.format("%.2f", val));
                setAlignment(Pos.CENTER_RIGHT);
            }
        });

        TableColumn<DispenseTask, Double> colDispensed = new TableColumn<>("已出(g)");
        colDispensed.setCellValueFactory(new PropertyValueFactory<>("dispensedGrams"));
        colDispensed.setPrefWidth(80);
        colDispensed.setCellFactory(col -> new TableCell<DispenseTask, Double>() {
            @Override
            protected void updateItem(Double val, boolean empty) {
                super.updateItem(val, empty);
                setText(empty || val == null ? null : String.format("%.2f", val));
                setAlignment(Pos.CENTER_RIGHT);
            }
        });

        TableColumn<DispenseTask, Void> colProgress = new TableColumn<>("进度");
        colProgress.setPrefWidth(150);
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
                    pb.setPrefWidth(80);
                    pb.setStyle("-fx-accent: #1E88E5;");
                    Label lbl = new Label(String.format("%.1f%%", task.getProgress()));
                    lbl.setFont(Font.font(10));
                    box.getChildren().addAll(pb, lbl);
                    setGraphic(box);
                }
            }
        });

        table.getColumns().addAll(colBinId, colMedicine, colTarget, colDispensed, colProgress);
        return table;
    }

    private VBox createRightPanel() {
        VBox right = new VBox(15);
        right.setPadding(new Insets(15));
        right.setPrefWidth(220);
        right.setStyle("-fx-background-color: #FAFAFA; -fx-border-color: #E0E0E0; -fx-border-width: 0 0 0 1;");

        Label ctrlTitle = new Label("控制面板");
        ctrlTitle.setFont(Font.font("Microsoft YaHei", FontWeight.BOLD, 16));
        ctrlTitle.setStyle("-fx-text-fill: #1a237e;");

        Separator sep1 = new Separator();

        btnLoadPrescription = new Button("加载处方文件");
        btnLoadPrescription.setPrefWidth(190);
        btnLoadPrescription.setStyle("-fx-background-color: #1565C0; -fx-text-fill: white; " +
                "-fx-font-size: 14px; -fx-font-family: 'Microsoft YaHei'; -fx-padding: 10 0;");
        btnLoadPrescription.setOnAction(e -> loadPrescriptionFile());

        prescriptionInfoLabel = new Label("未加载处方");
        prescriptionInfoLabel.setWrapText(true);
        prescriptionInfoLabel.setStyle("-fx-text-fill: #757575; -fx-font-size: 11px;");
        prescriptionInfoLabel.setPrefWidth(190);

        Separator sep2 = new Separator();

        btnStart = new Button("开始调剂");
        btnStart.setPrefWidth(190);
        btnStart.setStyle("-fx-background-color: #2E7D32; -fx-text-fill: white; " +
                "-fx-font-size: 16px; -fx-font-weight: bold; -fx-font-family: 'Microsoft YaHei'; -fx-padding: 14 0;");
        btnStart.setDisable(true);
        btnStart.setOnAction(e -> startDispense());

        btnEmergencyStop = new Button("紧急中止");
        btnEmergencyStop.setPrefWidth(190);
        btnEmergencyStop.setStyle("-fx-background-color: #C62828; -fx-text-fill: white; " +
                "-fx-font-size: 16px; -fx-font-weight: bold; -fx-font-family: 'Microsoft YaHei'; -fx-padding: 14 0;");
        btnEmergencyStop.setDisable(true);
        btnEmergencyStop.setOnAction(e -> doEmergencyStop());

        Separator sep3 = new Separator();

        Label progressTitle = new Label("调剂进度");
        progressTitle.setFont(Font.font("Microsoft YaHei", FontWeight.BOLD, 13));
        progressTitle.setStyle("-fx-text-fill: #1a237e;");

        overallProgress = new ProgressBar(0);
        overallProgress.setPrefWidth(190);
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

        Label versionLabel = new Label("TCM-Dispenser v1.0.0  |  JNI+C++ 跨语言架构");
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

            taskTable.getItems().setAll(currentPrescription.toDispenseTasks());
            btnStart.setDisable(false);

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
        confirm.setHeaderText("即将开始调剂");
        confirm.setContentText(String.format("处方号: %s\n患者: %s\n剂数: %d\n药味数: %d\n\n确认开始？",
                currentPrescription.getPrescriptionId(),
                currentPrescription.getPatientName(),
                currentPrescription.getDosageCount(),
                currentPrescription.getItems().size()));

        Optional<ButtonType> result = confirm.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
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
