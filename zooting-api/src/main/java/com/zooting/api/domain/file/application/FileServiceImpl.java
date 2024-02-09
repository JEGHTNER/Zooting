package com.zooting.api.domain.file.application;

import com.zooting.api.domain.file.dao.FileRepository;
import com.zooting.api.domain.file.dto.response.FileRes;
import com.zooting.api.domain.file.entity.File;
import com.zooting.api.domain.file.util.S3Util;
import com.zooting.api.global.common.code.ErrorCode;
import com.zooting.api.global.exception.BaseExceptionHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@Log4j2
@Service
@RequiredArgsConstructor
public class FileServiceImpl implements FileService {

    private final S3Util s3Util;
    private final FileRepository fileRepository;

    //TODO thumbnail
    @Override
    public List<FileRes> uploadFiles(List<MultipartFile> multipartFiles) throws IOException {
        return s3Util.uploadFiles(multipartFiles);
    }

    @Transactional
    @Override
    public void removeFile(Long fileId) {
        File deleteFile = fileRepository.findById(fileId).orElseThrow(() ->
                new BaseExceptionHandler(ErrorCode.NOT_FOUND_S3FILE));
        s3Util.remove(deleteFile.getFileDir());
        fileRepository.deleteById(fileId);
    }

    @Override
    public Object[] downloadFile(UUID S3Id) throws IOException {
        File file = fileRepository.findByS3Id(S3Id).orElseThrow(() ->
                new BaseExceptionHandler(ErrorCode.NOT_FOUND_S3FILE));
        return new Object[]{s3Util.downloadFile(file.getFileDir() + file.getFileName()),
                file.getOriginFileName()};
    }
}
